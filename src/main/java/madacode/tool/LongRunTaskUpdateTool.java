package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.core.model.ToolResult;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.longrunning.FeatureItem;
import madacode.longrunning.KnownIssue;
import madacode.longrunning.LongRunningTaskEvent;
import madacode.longrunning.LongRunningTaskStore;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class LongRunTaskUpdateTool implements Tool<LongRunTaskUpdateTool.Input> {

    public record Input(
            String action,
            String task_id,
            String feature_id,
            String issue_id,
            String description,
            String severity,
            String status,
            String new_status,
            String discovered_in,
            List<String> verification_steps,
            String text) {}

    @Override
    public String name() {
        return "longrun_task_update";
    }

    @Override
    public String description() {
        return "Safely update the current long-running task store during worker execution: "
                + "mark one feature passed, record or resolve a known issue, "
                + "update issue status, and append progress.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("action", ToolSchemas.stringEnumProperty(mapper,
                "Task-store update action",
                "mark_feature_passed",
                "record_issue",
                "resolve_issue",
                "update_issue_status",
                "append_progress"));
        properties.set("task_id", ToolSchemas.stringProperty(
                mapper, "Optional current task id. If present, it must match the active session task."));
        properties.set("feature_id", ToolSchemas.stringProperty(
                mapper, "Feature id for mark_feature_passed."));
        properties.set("issue_id", ToolSchemas.stringProperty(
                mapper, "Issue id for record_issue, resolve_issue, or update_issue_status."));
        properties.set("description", ToolSchemas.stringProperty(
                mapper, "Known issue description for record_issue."));
        properties.set("severity", ToolSchemas.stringProperty(
                mapper, "Known issue severity for record_issue."));
        properties.set("status", ToolSchemas.stringEnumProperty(mapper,
                "Known issue status for record_issue. Defaults to open.",
                "open", "blocked"));
        properties.set("new_status", ToolSchemas.stringEnumProperty(mapper,
                "New status for update_issue_status. Allowed transitions: open<->blocked, open/blocked->resolved.",
                "open", "blocked", "resolved"));
        properties.set("discovered_in", ToolSchemas.stringProperty(
                mapper, "Where the issue was discovered. Defaults to the current long-running stage."));
        properties.set("verification_steps", ToolSchemas.arrayProperty(
                mapper, "Verification steps for record_issue, or verification evidence for mark_feature_passed.",
                stringItem(mapper)));
        properties.set("text", ToolSchemas.stringProperty(
                mapper, "Progress text for append_progress."));
        return ToolSchemas.objectSchema(mapper, properties, "action");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        Objects.requireNonNull(input, "input");
        ConversationSession session = context.session();
        if (session.workflowMode() != SessionMode.LONG_RUNNING) {
            return failed("Long-running mode is not active for this session.");
        }
        if (session.longRunningStage() != LongRunningStage.RUNNING) {
            return failed("longrun_task_update is only available in the RUNNING stage. Current stage: "
                    + session.longRunningStage());
        }
        if (session.longRunningTaskId() == null || session.longRunningTaskId().isBlank()) {
            return failed("No initialized long-running task is active for this session.");
        }
        if (!session.isLongRunningWorkerSession()) {
            return failed("longrun_task_update is only available in a long-running worker session.");
        }
        LongRunningTaskStore storeForEvent = null;
        String taskIdForEvent = null;
        String action = input.action() == null ? "" : input.action().strip().toLowerCase(Locale.ROOT);
        try {
            String taskId = activeTaskId(input, session);
            if (taskId == null) {
                return failed("No initialized long-running task is active for this session.");
            }

            LongRunningTaskStore store = new LongRunningTaskStore(context.workingDirectory());
            storeForEvent = store;
            taskIdForEvent = taskId;
            store.validateTaskDirectory(taskId);
            store.requireRunning(taskId);
            session.setLongRunningTaskDirectory(store.taskDirectoryPath(taskId).toString());

            ToolResult result = switch (action) {
                case "mark_feature_passed" -> markFeaturePassed(store, taskId, input, session);
                case "record_issue" -> recordIssue(store, taskId, input, session);
                case "resolve_issue" -> resolveIssue(store, taskId, input, session);
                case "update_issue_status" -> updateIssueStatus(store, taskId, input, session);
                case "append_progress" -> appendProgress(store, taskId, input, session);
                default -> failed("Unsupported long-running task update action: " + safe(input.action()));
            };
            appendTaskUpdateEvent(store, taskId, session, action, result, input);
            return result;
        } catch (RuntimeException exception) {
            ToolResult result = failed(exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage());
            if (storeForEvent != null && taskIdForEvent != null) {
                appendTaskUpdateEvent(storeForEvent, taskIdForEvent, session, action, result, input);
            }
            return result;
        }
    }

    private ToolResult markFeaturePassed(
            LongRunningTaskStore store, String taskId, Input input, ConversationSession session) {
        String featureId = requireNonBlank(input.feature_id(), "feature_id");
        if (input.verification_steps() == null || input.verification_steps().stream().noneMatch(
                value -> value != null && !value.isBlank())) {
            return failed("mark_feature_passed requires non-empty verification evidence.");
        }
        FeatureItem feature = store.markFeaturePassed(taskId, featureId, input.verification_steps());
        return succeeded("Feature marked passed: " + feature.id());
    }

    private ToolResult recordIssue(
            LongRunningTaskStore store,
            String taskId,
            Input input,
            ConversationSession session) {
        String issueId = requireNonBlank(input.issue_id(), "issue_id");
        String status = input.status() == null || input.status().isBlank()
                ? "open"
                : input.status().strip().toLowerCase(Locale.ROOT);
        KnownIssue issue = new KnownIssue(
                issueId,
                requireNonBlank(input.description(), "description"),
                requireNonBlank(input.severity(), "severity"),
                status,
                input.discovered_in() == null || input.discovered_in().isBlank()
                        ? currentStageName(session)
                        : input.discovered_in().strip(),
                input.verification_steps(),
                Instant.now(),
                null);
        store.recordIssue(taskId, issue);
        return succeeded("Known issue recorded: " + issue.id() + " (" + issue.status() + ").");
    }

    private ToolResult resolveIssue(
            LongRunningTaskStore store, String taskId, Input input, ConversationSession session) {
        String issueId = requireNonBlank(input.issue_id(), "issue_id");
        KnownIssue issue = store.markIssueResolved(taskId, issueId);
        return succeeded("Known issue resolved: " + issue.id());
    }

    private ToolResult updateIssueStatus(
            LongRunningTaskStore store, String taskId, Input input, ConversationSession session) {
        String issueId = requireNonBlank(input.issue_id(), "issue_id");
        String newStatus = requireNonBlank(input.new_status(), "new_status");
        KnownIssue issue = store.updateIssueStatus(taskId, issueId, newStatus);
        return succeeded("Issue " + issue.id() + " status updated to " + issue.status() + ".");
    }

    private ToolResult appendProgress(
            LongRunningTaskStore store, String taskId, Input input, ConversationSession session) {
        String text = requireNonBlank(input.text(), "text");
        store.appendProgress(taskId, text.endsWith(System.lineSeparator()) ? text : text + System.lineSeparator());
        return succeeded("Progress appended for " + taskId + ".");
    }

    private void appendTaskUpdateEvent(
            LongRunningTaskStore store,
            String taskId,
            ConversationSession session,
            String action,
            ToolResult result,
            Input input) {
        try {
            store.appendEvent(taskId, LongRunningTaskEvent.of(
                    "task_update",
                    taskId,
                    session.sessionId(),
                    session.longRunningStage() == null ? null : session.longRunningStage().name(),
                    action,
                    result.success(),
                    result.output(),
                    eventDetails(input)));
        } catch (RuntimeException ignored) {
            // Event logging is diagnostic. A failed append must not turn an
            // already-applied task-store mutation into a failed tool result.
        }
    }

    private static Map<String, String> eventDetails(Input input) {
        java.util.LinkedHashMap<String, String> details = new java.util.LinkedHashMap<>();
        putIfPresent(details, "featureId", input.feature_id());
        putIfPresent(details, "issueId", input.issue_id());
        putIfPresent(details, "newStatus", input.new_status());
        putIfPresent(details, "severity", input.severity());
        if (input.verification_steps() != null) {
            details.put("verificationEvidence", String.join("; ", input.verification_steps()));
        }
        return Map.copyOf(details);
    }

    private static void putIfPresent(Map<String, String> details, String key, String value) {
        if (value != null && !value.isBlank()) {
            details.put(key, value.strip());
        }
    }

    private static String activeTaskId(Input input, ConversationSession session) {
        String active = session.longRunningTaskId();
        if (input.task_id() == null || input.task_id().isBlank()) {
            return active;
        }
        String requested = input.task_id().strip();
        if (!requested.equals(active)) {
            throw new IllegalArgumentException("task_id does not match the active long-running task.");
        }
        return active;
    }

    private static ObjectNode stringItem(ObjectMapper mapper) {
        ObjectNode item = mapper.createObjectNode();
        item.put("type", "string");
        return item;
    }

    private static ToolResult succeeded(String output) {
        return new ToolResult("longrun_task_update", true, output);
    }

    private static ToolResult failed(String output) {
        return new ToolResult("longrun_task_update", false, output);
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String stripped = value.strip();
        if (stripped.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return stripped;
    }

    private static String safe(String value) {
        return value == null ? "(missing)" : value;
    }

    private static String currentStageName(ConversationSession session) {
        return session.longRunningStage() == null ? "RUNNING" : session.longRunningStage().name();
    }
}
