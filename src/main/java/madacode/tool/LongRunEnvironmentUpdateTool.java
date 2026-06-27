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
import madacode.longrunning.LongRunningTaskInitializer;
import madacode.longrunning.LongRunningTaskMetadata;
import madacode.longrunning.LongRunningTaskStore;
import madacode.tool.schema.OptionalSchemaProperty;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class LongRunEnvironmentUpdateTool implements Tool<LongRunEnvironmentUpdateTool.Input> {

    public record Input(
            String action,
            @OptionalSchemaProperty
            String task_id,
            @OptionalSchemaProperty
            String title,
            @OptionalSchemaProperty
            String reason,
            @OptionalSchemaProperty
            String plan_summary,
            @OptionalSchemaProperty
            List<LongRunEnvironmentSupport.FeatureInput> features,
            @OptionalSchemaProperty
            List<LongRunEnvironmentSupport.IssueInput> issues,
            @OptionalSchemaProperty
            String text,
            @OptionalSchemaProperty
            String feature_id,
            @OptionalSchemaProperty
            String issue_id,
            @OptionalSchemaProperty
            String description,
            @OptionalSchemaProperty
            String severity,
            @OptionalSchemaProperty
            String status,
            @OptionalSchemaProperty
            String new_status,
            @OptionalSchemaProperty
            String discovered_in,
            @OptionalSchemaProperty
            List<String> verification_steps) {}

    @Override
    public String name() {
        return ToolNames.LONGRUN_ENVIRONMENT_UPDATE;
    }

    @Override
    public String description() {
        return "Create and update the long-running environment through the task store. "
                + "Controller: after the user confirms the plan is ready, use action=initialize_environment with "
                + "the complete task summary, feature list, known issues list, and progress note before applying RUNNING. "
                + "Worker: use this for durable progress, feature pass, and known issue updates. "
                + "Never edit .mada/long-running files with generic file tools.";
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
    public boolean isPlanModeSafe() {
        return false;
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("action", ToolSchemas.stringEnumProperty(
                mapper,
                "Environment update action. Controller actions: initialize_environment, update_task_summary, replace_features, replace_known_issues, append_progress. Worker actions: append_progress, mark_feature_passed, record_issue, resolve_issue, update_issue_status.",
                "initialize_environment",
                "update_task_summary",
                "replace_features",
                "replace_known_issues",
                "append_progress",
                "mark_feature_passed",
                "record_issue",
                "resolve_issue",
                "update_issue_status"));
        properties.set("task_id", ToolSchemas.stringProperty(
                mapper, "Optional task id. If present, it must match the active long-running session task."));
        properties.set("title", ToolSchemas.stringProperty(
                mapper, "Task title for initialize_environment or update_task_summary."));
        properties.set("reason", ToolSchemas.stringProperty(
                mapper, "Optional durable reason note for initialize_environment or update_task_summary."));
        properties.set("plan_summary", ToolSchemas.stringProperty(
                mapper, "Complete task summary/plan for initialize_environment or update_task_summary."));
        properties.set("features", ToolSchemas.arrayProperty(
                mapper,
                "Complete feature list for initialize_environment or replace_features. Feature ids must be stable.",
                ToolSchemas.schemaFromRecord(mapper, LongRunEnvironmentSupport.FeatureInput.class)));
        properties.set("issues", ToolSchemas.arrayProperty(
                mapper,
                "Complete known issues list for initialize_environment or replace_known_issues. Use [] when there are no known issues.",
                ToolSchemas.schemaFromRecord(mapper, LongRunEnvironmentSupport.IssueInput.class)));
        properties.set("text", ToolSchemas.stringProperty(
                mapper, "Progress text for initialize_environment or append_progress."));
        properties.set("feature_id", ToolSchemas.stringProperty(
                mapper, "Feature id for mark_feature_passed."));
        properties.set("issue_id", ToolSchemas.stringProperty(
                mapper, "Issue id for record_issue, resolve_issue, or update_issue_status."));
        properties.set("description", ToolSchemas.stringProperty(
                mapper, "Known issue description for record_issue."));
        properties.set("severity", ToolSchemas.stringProperty(
                mapper, "Known issue severity for record_issue."));
        properties.set("status", ToolSchemas.stringEnumProperty(
                mapper, "Known issue status for record_issue. Defaults to open.", "open", "blocked"));
        properties.set("new_status", ToolSchemas.stringEnumProperty(
                mapper,
                "New status for update_issue_status. Allowed transitions: open<->blocked, open/blocked->deferred, deferred->open, open/blocked/deferred->resolved.",
                "open", "blocked", "deferred", "resolved"));
        properties.set("discovered_in", ToolSchemas.stringProperty(
                mapper, "Where the issue was discovered. Defaults to the current long-running stage."));
        properties.set("verification_steps", ToolSchemas.arrayProperty(
                mapper,
                "Verification steps for record_issue, or verification evidence for mark_feature_passed.",
                ToolSchemas.stringItem(mapper)));
        return ToolSchemas.objectSchema(mapper, properties, "action");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        Objects.requireNonNull(input, "input");
        ConversationSession session = context.session();
        if (session.workflowMode() != SessionMode.LONG_RUNNING) {
            return failed("Long-running mode is not active for this session.");
        }

        String action = input.action() == null ? "" : input.action().strip().toLowerCase(Locale.ROOT);
        LongRunningTaskStore store = new LongRunningTaskStore(context.workingDirectory());
        String taskIdForEvent = null;
        try {
            ToolResult result;
            if (session.isLongRunningWorkerSession()) {
                requireWorkerSession(session);
                String taskId = requireExistingTaskId(input, session, store);
                taskIdForEvent = taskId;
                result = executeWorkerAction(action, store, taskId, input, session);
            } else {
                requireControlStage(session);
                result = executeControllerAction(action, store, input, session);
                taskIdForEvent = session.longRunningTaskId();
            }
            if (taskIdForEvent != null && !taskIdForEvent.isBlank()) {
                appendEvent(store, taskIdForEvent, session, action, result, input);
            }
            return result;
        } catch (RuntimeException exception) {
            ToolResult result = failed(safeMessage(exception));
            if (taskIdForEvent != null && !taskIdForEvent.isBlank()) {
                appendEvent(store, taskIdForEvent, session, action, result, input);
            }
            return result;
        }
    }

    private static ToolResult executeControllerAction(
            String action,
            LongRunningTaskStore store,
            Input input,
            ConversationSession session) {
        return switch (action) {
            case "initialize_environment" -> initializeEnvironment(store, input, session);
            case "update_task_summary" -> updateTaskSummary(store, requireExistingTaskId(input, session, store), input, session);
            case "replace_features" -> replaceFeatures(store, requireExistingTaskId(input, session, store), input);
            case "replace_known_issues" -> replaceKnownIssues(store, requireExistingTaskId(input, session, store), input, session);
            case "append_progress" -> appendProgress(store, requireExistingTaskId(input, session, store), input);
            case "mark_feature_passed", "record_issue", "resolve_issue", "update_issue_status" ->
                    failed(action + " is a worker action. The Controller should only initialize or revise the environment while DRAFT or INTERRUPT.");
            default -> failed("Unsupported long-running environment update action: " + safe(input.action()));
        };
    }

    private static ToolResult executeWorkerAction(
            String action,
            LongRunningTaskStore store,
            String taskId,
            Input input,
            ConversationSession session) {
        return switch (action) {
            case "append_progress" -> appendProgress(store, taskId, input);
            case "mark_feature_passed" -> markFeaturePassed(store, taskId, input);
            case "record_issue" -> recordIssue(store, taskId, input, session);
            case "resolve_issue" -> resolveIssue(store, taskId, input);
            case "update_issue_status" -> updateIssueStatus(store, taskId, input);
            case "initialize_environment", "update_task_summary", "replace_features", "replace_known_issues" ->
                    failed(action + " is a Controller action. Workers must not reshape the agreed environment.");
            default -> failed("Unsupported long-running environment update action: " + safe(input.action()));
        };
    }

    private static ToolResult initializeEnvironment(
            LongRunningTaskStore store,
            Input input,
            ConversationSession session) {
        String summary = LongRunEnvironmentSupport.requireNonBlank(input.plan_summary(), "plan_summary");
        List<LongRunEnvironmentSupport.FeatureInput> featureInputs = List.copyOf(
                Objects.requireNonNullElse(input.features(), List.of()));
        if (featureInputs.isEmpty()) {
            throw new IllegalArgumentException("initialize_environment requires a non-empty features list.");
        }
        if (input.text() == null || input.text().isBlank()) {
            throw new IllegalArgumentException("initialize_environment requires a progress text note.");
        }
        if (session.longRunningTaskId() == null || session.longRunningTaskId().isBlank()) {
            LongRunningTaskInitializer initializer = new LongRunningTaskInitializer(
                    store,
                    LongRunningTaskInitializer.TaskIdGenerator::defaultNewTaskId);
            initializer.ensurePlanningTask(session, summary);
        }
        String taskId = requireExistingTaskId(input, session, store);
        LongRunningTaskMetadata updated = writeTaskSummary(store, taskId, input, session, summary);
        replaceFeatureList(store, taskId, featureInputs);
        replaceKnownIssueList(store, taskId, input.issues(), currentStageName(session));
        appendProgressText(store, taskId, input.text());
        return succeeded("Initialized long-running environment for " + taskId
                + " with " + featureInputs.size() + " feature(s). Current status: " + updated.status() + ".");
    }

    private static ToolResult updateTaskSummary(
            LongRunningTaskStore store,
            String taskId,
            Input input,
            ConversationSession session) {
        String summary = LongRunEnvironmentSupport.requireNonBlank(input.plan_summary(), "plan_summary");
        writeTaskSummary(store, taskId, input, session, summary);
        return succeeded("Updated long-running task summary for " + taskId + ".");
    }

    private static LongRunningTaskMetadata writeTaskSummary(
            LongRunningTaskStore store,
            String taskId,
            Input input,
            ConversationSession session,
            String summary) {
        LongRunningTaskMetadata current = store.loadTask(taskId);
        String title = LongRunEnvironmentSupport.deriveTitle(input.title(), summary, current.title());
        LongRunningTaskMetadata updated = store.updateTaskShell(
                taskId,
                title,
                current.status(),
                LongRunEnvironmentSupport.normalizeOptional(input.reason()),
                current.executionStarted(),
                session.sessionId(),
                summary);
        session.setLongRunningTaskTitle(updated.title());
        session.setLongRunningPlanSummary(summary);
        session.setLongRunningReason(updated.reason());
        session.setLongRunningTaskDirectory(store.taskDirectoryPath(taskId).toString());
        return updated;
    }

    private static ToolResult replaceFeatures(LongRunningTaskStore store, String taskId, Input input) {
        List<LongRunEnvironmentSupport.FeatureInput> featureInputs = List.copyOf(
                Objects.requireNonNullElse(input.features(), List.of()));
        if (featureInputs.isEmpty()) {
            throw new IllegalArgumentException("replace_features requires a non-empty features list.");
        }
        replaceFeatureList(store, taskId, featureInputs);
        return succeeded("Replaced feature list for " + taskId + " (" + featureInputs.size() + " feature(s)).");
    }

    private static void replaceFeatureList(
            LongRunningTaskStore store,
            String taskId,
            List<LongRunEnvironmentSupport.FeatureInput> featureInputs) {
        List<FeatureItem> features = LongRunEnvironmentSupport.featureItemsForReplacement(
                featureInputs,
                store.readFeatureList(taskId));
        store.replaceFeatureList(taskId, features);
    }

    private static ToolResult replaceKnownIssues(
            LongRunningTaskStore store,
            String taskId,
            Input input,
            ConversationSession session) {
        List<LongRunEnvironmentSupport.IssueInput> issueInputs = List.copyOf(
                Objects.requireNonNullElse(input.issues(), List.of()));
        replaceKnownIssueList(store, taskId, issueInputs, currentStageName(session));
        return succeeded("Replaced known issues for " + taskId + " (" + issueInputs.size() + " issue(s)).");
    }

    private static void replaceKnownIssueList(
            LongRunningTaskStore store,
            String taskId,
            List<LongRunEnvironmentSupport.IssueInput> issueInputs,
            String defaultDiscoveredIn) {
        store.replaceKnownIssues(
                taskId,
                LongRunEnvironmentSupport.issueItemsForReplacement(issueInputs, defaultDiscoveredIn));
    }

    private static ToolResult appendProgress(LongRunningTaskStore store, String taskId, Input input) {
        appendProgressText(store, taskId, input.text());
        return succeeded("Progress appended for " + taskId + ".");
    }

    private static void appendProgressText(LongRunningTaskStore store, String taskId, String text) {
        String value = LongRunEnvironmentSupport.requireNonBlank(text, "text");
        store.appendProgress(taskId, value.endsWith(System.lineSeparator()) ? value : value + System.lineSeparator());
    }

    private static ToolResult markFeaturePassed(LongRunningTaskStore store, String taskId, Input input) {
        String featureId = LongRunEnvironmentSupport.requireNonBlank(input.feature_id(), "feature_id");
        if (input.verification_steps() == null || input.verification_steps().stream().noneMatch(
                value -> value != null && !value.isBlank())) {
            return failed("mark_feature_passed requires non-empty verification evidence in verification_steps.");
        }
        FeatureItem feature = store.markFeaturePassed(taskId, featureId, input.verification_steps());
        return succeeded("Feature marked passed: " + feature.id());
    }

    private static ToolResult recordIssue(
            LongRunningTaskStore store,
            String taskId,
            Input input,
            ConversationSession session) {
        String status = input.status() == null || input.status().isBlank()
                ? "open"
                : input.status().strip().toLowerCase(Locale.ROOT);
        KnownIssue issue = new KnownIssue(
                LongRunEnvironmentSupport.requireNonBlank(input.issue_id(), "issue_id"),
                LongRunEnvironmentSupport.requireNonBlank(input.description(), "description"),
                LongRunEnvironmentSupport.requireNonBlank(input.severity(), "severity"),
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

    private static ToolResult resolveIssue(LongRunningTaskStore store, String taskId, Input input) {
        String issueId = LongRunEnvironmentSupport.requireNonBlank(input.issue_id(), "issue_id");
        KnownIssue issue = store.markIssueResolved(taskId, issueId);
        return succeeded("Known issue resolved: " + issue.id());
    }

    private static ToolResult updateIssueStatus(LongRunningTaskStore store, String taskId, Input input) {
        String issueId = LongRunEnvironmentSupport.requireNonBlank(input.issue_id(), "issue_id");
        String newStatus = LongRunEnvironmentSupport.requireNonBlank(input.new_status(), "new_status");
        KnownIssue issue = store.updateIssueStatus(taskId, issueId, newStatus);
        return succeeded("Issue " + issue.id() + " status updated to " + issue.status() + ".");
    }

    private static void requireControlStage(ConversationSession session) {
        LongRunningStage stage = session.longRunningStage();
        if (stage != LongRunningStage.DRAFT && stage != LongRunningStage.INTERRUPT) {
            throw new IllegalStateException(
                    "longrun_environment_update Controller actions are only available in DRAFT or INTERRUPT. Current stage: "
                            + stage);
        }
    }

    private static void requireWorkerSession(ConversationSession session) {
        if (session.longRunningStage() != LongRunningStage.RUNNING) {
            throw new IllegalStateException(
                    "Worker environment updates are only available in RUNNING. Current stage: "
                            + session.longRunningStage());
        }
        if (session.longRunningTaskId() == null || session.longRunningTaskId().isBlank()) {
            throw new IllegalStateException("No initialized long-running task is active for this worker.");
        }
    }

    private static String requireExistingTaskId(
            Input input,
            ConversationSession session,
            LongRunningTaskStore store) {
        String taskId = LongRunEnvironmentSupport.activeTaskId(input.task_id(), session);
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalStateException(
                    "Long-running environment is not initialized. Use action=initialize_environment first.");
        }
        store.validateTaskDirectory(taskId);
        if (session.isLongRunningWorkerSession()) {
            store.requireRunning(taskId);
        } else {
            LongRunningTaskMetadata metadata = store.loadTask(taskId);
            LongRunningStage durableStage = LongRunningStage.fromWire(metadata.status())
                    .orElseThrow(() -> new IllegalStateException(
                            "Unsupported task status: " + metadata.status()));
            if (durableStage != LongRunningStage.DRAFT && durableStage != LongRunningStage.INTERRUPT) {
                throw new IllegalStateException(
                        "Controller environment updates require durable task status DRAFT or INTERRUPT. Current status: "
                                + metadata.status());
            }
        }
        session.setLongRunningTaskDirectory(store.taskDirectoryPath(taskId).toString());
        return taskId;
    }

    private static void appendEvent(
            LongRunningTaskStore store,
            String taskId,
            ConversationSession session,
            String action,
            ToolResult result,
            Input input) {
        try {
            store.appendEvent(taskId, LongRunningTaskEvent.of(
                    ToolNames.LONGRUN_ENVIRONMENT_UPDATE,
                    taskId,
                    session.sessionId(),
                    session.longRunningStage() == null ? null : session.longRunningStage().name(),
                    action,
                    result.success(),
                    result.output(),
                    eventDetails(input)));
        } catch (RuntimeException ignored) {
            // Event logging is diagnostic. The task-store mutation already
            // succeeded or failed before this best-effort event append.
        }
    }

    private static Map<String, String> eventDetails(Input input) {
        LinkedHashMap<String, String> details = new LinkedHashMap<>();
        LongRunEnvironmentSupport.putIfPresent(details, "featureId", input.feature_id());
        LongRunEnvironmentSupport.putIfPresent(details, "issueId", input.issue_id());
        LongRunEnvironmentSupport.putIfPresent(details, "newStatus", input.new_status());
        LongRunEnvironmentSupport.putIfPresent(details, "severity", input.severity());
        if (input.features() != null) {
            details.put("featureCount", String.valueOf(input.features().size()));
        }
        if (input.issues() != null) {
            details.put("issueCount", String.valueOf(input.issues().size()));
        }
        if (input.verification_steps() != null) {
            details.put("verificationEvidence", String.join("; ", input.verification_steps()));
        }
        return Map.copyOf(details);
    }

    private static String currentStageName(ConversationSession session) {
        return session.longRunningStage() == null ? "LONG_RUNNING" : session.longRunningStage().name();
    }

    private static String safe(String value) {
        return value == null ? "(missing)" : value;
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static ToolResult succeeded(String output) {
        return new ToolResult(ToolNames.LONGRUN_ENVIRONMENT_UPDATE, true, output);
    }

    private static ToolResult failed(String output) {
        return new ToolResult(ToolNames.LONGRUN_ENVIRONMENT_UPDATE, false, output);
    }
}
