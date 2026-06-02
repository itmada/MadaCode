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
import madacode.longrunning.LongRunningTaskStore;
import madacode.longrunning.LongRunningTaskStoreException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class LongRunTaskUpdateTool implements Tool<LongRunTaskUpdateTool.Input> {

    public record FeatureInput(
            String id,
            String category,
            String priority,
            String description,
            List<String> depends_on,
            List<String> verification_steps) {}

    public record Input(
            String action,
            String task_id,
            List<FeatureInput> features,
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
        return "Safely update the current long-running task store: seed feature_list.json, "
                + "mark one feature passed, record or resolve a known issue, update issue status, "
                + "append progress, mark task complete, or cancel task.";
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
                "write_initial_feature_list",
                "mark_feature_passed",
                "record_issue",
                "resolve_issue",
                "update_issue_status",
                "append_progress",
                "mark_task_complete",
                "cancel_task"));
        properties.set("task_id", ToolSchemas.stringProperty(
                mapper, "Optional current task id. If present, it must match the active session task."));
        properties.set("features", ToolSchemas.arrayProperty(
                mapper,
                "Initial feature list for write_initial_feature_list. Each item uses id, category, priority, description, depends_on, and verification_steps.",
                objectItem(mapper)));
        properties.set("feature_id", ToolSchemas.stringProperty(
                mapper, "Feature id for mark_feature_passed."));
        properties.set("issue_id", ToolSchemas.stringProperty(
                mapper, "Issue id for record_issue or resolve_issue."));
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
                mapper, "Verification steps for record_issue.",
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
        if (session.longRunningStage() != LongRunningStage.EXECUTING) {
            return failed("longrun_task_update is only available in the EXECUTING stage. Current stage: "
                    + session.longRunningStage());
        }
        if (session.longRunningTaskId() == null || session.longRunningTaskId().isBlank()) {
            return failed("No initialized long-running task is active for this session.");
        }
        try {
            String taskId = activeTaskId(input, session);
            if (taskId == null) {
                return failed("No initialized long-running task is active for this session.");
            }

            LongRunningTaskStore store = new LongRunningTaskStore(context.workingDirectory());
            String action = input.action() == null ? "" : input.action().strip().toLowerCase(Locale.ROOT);
            return switch (action) {
                case "write_initial_feature_list" -> writeInitialFeatureList(store, taskId, input);
                case "mark_feature_passed" -> markFeaturePassed(store, taskId, input);
                case "record_issue" -> recordIssue(store, taskId, input, session);
                case "resolve_issue" -> resolveIssue(store, taskId, input);
                case "update_issue_status" -> updateIssueStatus(store, taskId, input);
                case "append_progress" -> appendProgress(store, taskId, input);
                case "mark_task_complete" -> markTaskComplete(store, taskId, session);
                case "cancel_task" -> cancelTask(store, taskId, session);
                default -> failed("Unsupported long-running task update action: " + safe(input.action()));
            };
        } catch (RuntimeException exception) {
            return failed(exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage());
        }
    }

    private ToolResult writeInitialFeatureList(LongRunningTaskStore store, String taskId, Input input) {
        List<FeatureInput> featureInputs = input.features() == null ? List.of() : input.features();
        if (featureInputs.isEmpty()) {
            return failed("features must be non-empty for write_initial_feature_list.");
        }
        List<FeatureItem> features = featureInputs.stream()
                .map(feature -> new FeatureItem(
                        feature.id(),
                        feature.category(),
                        feature.priority(),
                        feature.description(),
                        feature.depends_on(),
                        feature.verification_steps(),
                        false))
                .toList();
        store.writeInitialFeatureList(taskId, features);
        return succeeded("Initial feature list written for " + taskId + " (" + features.size() + " feature(s)).");
    }

    private ToolResult markFeaturePassed(LongRunningTaskStore store, String taskId, Input input) {
        String featureId = requireNonBlank(input.feature_id(), "feature_id");
        FeatureItem feature = store.markFeaturePassed(taskId, featureId);
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

    private ToolResult resolveIssue(LongRunningTaskStore store, String taskId, Input input) {
        String issueId = requireNonBlank(input.issue_id(), "issue_id");
        KnownIssue issue = store.markIssueResolved(taskId, issueId);
        return succeeded("Known issue resolved: " + issue.id());
    }

    private ToolResult updateIssueStatus(LongRunningTaskStore store, String taskId, Input input) {
        String issueId = requireNonBlank(input.issue_id(), "issue_id");
        String newStatus = requireNonBlank(input.new_status(), "new_status");
        KnownIssue issue = store.updateIssueStatus(taskId, issueId, newStatus);
        return succeeded("Issue " + issue.id() + " status updated to " + issue.status() + ".");
    }

    private ToolResult appendProgress(LongRunningTaskStore store, String taskId, Input input) {
        String text = requireNonBlank(input.text(), "text");
        store.appendProgress(taskId, text.endsWith(System.lineSeparator()) ? text : text + System.lineSeparator());
        return succeeded("Progress appended for " + taskId + ".");
    }

    private ToolResult markTaskComplete(LongRunningTaskStore store, String taskId, ConversationSession session) {
        store.markTaskCompleted(taskId);
        session.setLongRunningStage(LongRunningStage.COMPLETED);
        return succeeded("Task " + taskId + " marked as completed.");
    }

    private ToolResult cancelTask(LongRunningTaskStore store, String taskId, ConversationSession session) {
        store.cancelTask(taskId);
        session.setLongRunningStage(LongRunningStage.CANCELLED);
        return succeeded("Task " + taskId + " cancelled.");
    }

    private static String activeTaskId(Input input, ConversationSession session) {
        String active = session.longRunningTaskId();
        if (input.task_id() == null || input.task_id().isBlank()) {
            return active;
        }
        String requested = input.task_id().strip();
        if (!requested.equals(active)) {
            throw new IllegalArgumentException(
                    "task_id does not match the active long-running task.");
        }
        return active;
    }

    private static ObjectNode objectItem(ObjectMapper mapper) {
        ObjectNode item = mapper.createObjectNode();
        item.put("type", "object");
        return item;
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
        return session.longRunningStage() == null
                ? "EXECUTING"
                : session.longRunningStage().name();
    }
}
