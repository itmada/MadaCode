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
import madacode.longrunning.LongRunningTaskMetadata;
import madacode.longrunning.LongRunningTaskStore;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class LongRunPlanUpdateTool implements Tool<LongRunPlanUpdateTool.Input> {

    public record FeatureInput(
            String id,
            String category,
            String priority,
            String description,
            List<String> depends_on,
            List<String> verification_steps,
            Boolean passes) {}

    public record IssueInput(
            String id,
            String description,
            String severity,
            String status,
            String discovered_in,
            List<String> verification_steps) {}

    public record Input(
            String action,
            String task_id,
            String title,
            String reason,
            String plan_summary,
            List<FeatureInput> features,
            List<IssueInput> issues,
            String text) {}

    @Override
    public String name() {
        return "longrun_plan_update";
    }

    @Override
    public String description() {
        return "Maintain the durable long-running task-store draft in DRAFT or INTERRUPT control sessions by updating "
                + "task.json summary, feature_list.json, known_issues.json, progress.txt, and events.";
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
        properties.set("action", ToolSchemas.stringEnumProperty(mapper,
                "Durable task-store draft update action",
                "update_task_summary",
                "update_plan_summary",
                "replace_feature_list",
                "replace_known_issues",
                "append_progress"));
        properties.set("task_id", ToolSchemas.stringProperty(
                mapper, "Optional task id. If present, it must match the active session task."));
        properties.set("title", ToolSchemas.stringProperty(
                mapper, "Optional replacement task title for update_task_summary."));
        properties.set("reason", ToolSchemas.stringProperty(
                mapper, "Optional draft reason such as requirements_updated for update_task_summary."));
        properties.set("plan_summary", ToolSchemas.stringProperty(
                mapper, "Updated durable structured task summary for update_task_summary."));
        properties.set("features", ToolSchemas.arrayProperty(
                mapper,
                "Full replacement feature list for replace_feature_list.",
                ToolSchemas.schemaFromRecord(mapper, FeatureInput.class)));
        properties.set("issues", ToolSchemas.arrayProperty(
                mapper,
                "Full replacement known issue list for replace_known_issues.",
                ToolSchemas.schemaFromRecord(mapper, IssueInput.class)));
        properties.set("text", ToolSchemas.stringProperty(
                mapper, "Progress text to append for append_progress."));
        return ToolSchemas.objectSchema(mapper, properties, "action");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        Objects.requireNonNull(input, "input");
        ConversationSession session = context.session();
        if (session.workflowMode() != SessionMode.LONG_RUNNING) {
            return failed("Long-running mode is not active for this session.");
        }
        if (session.isLongRunningWorkerSession()) {
            return failed("longrun_plan_update is only available in the control session.");
        }
        if (!isDraftStage(session.longRunningStage())) {
            return failed("longrun_plan_update is only available while the task is in DRAFT or INTERRUPT. Current stage: "
                    + session.longRunningStage());
        }
        if (session.longRunningTaskId() == null || session.longRunningTaskId().isBlank()) {
            return failed("No long-running task is active for this session.");
        }

        String action = normalizeAction(input.action());
        String taskId = activeTaskId(input, session);
        LongRunningTaskStore store = new LongRunningTaskStore(context.workingDirectory());
        try {
            store.validateTaskDirectory(taskId);
            LongRunningTaskMetadata currentTask = store.loadTask(taskId);
            LongRunningStage durableStage = LongRunningStage.fromWire(currentTask.status())
                    .orElseThrow(() -> new IllegalStateException(
                            "Unsupported task status: " + currentTask.status()));
            if (!isDraftStage(durableStage)) {
                return failed("longrun_plan_update is only available while the durable task is DRAFT or INTERRUPT. "
                        + "Current task status: " + currentTask.status());
            }
            session.setLongRunningTaskDirectory(store.taskDirectoryPath(taskId).toString());

            ToolResult result = switch (action) {
                case "update_task_summary" -> updatePlanSummary(store, taskId, input, session);
                case "replace_feature_list" -> replaceFeatureList(store, taskId, input);
                case "replace_known_issues" -> replaceKnownIssues(store, taskId, input);
                case "append_progress" -> appendProgress(store, taskId, input);
                default -> failed("Unsupported long-running draft update action: " + safe(input.action()));
            };

            appendEvent(store, taskId, session, action, result, input);
            return result;
        } catch (RuntimeException exception) {
            ToolResult result = failed(exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage());
            appendEvent(store, taskId, session, action, result, input);
            return result;
        }
    }

    private static String normalizeAction(String action) {
        String normalized = action == null ? "" : action.strip().toLowerCase(Locale.ROOT);
        // Compatibility alias for drafts or transcripts created before the
        // global update_plan tool owned visible execution progress.
        return "update_plan_summary".equals(normalized) ? "update_task_summary" : normalized;
    }

    private ToolResult updatePlanSummary(
            LongRunningTaskStore store,
            String taskId,
            Input input,
            ConversationSession session) {
        String summary = requireNonBlank(input.plan_summary(), "plan_summary");
        String title = input.title() == null || input.title().isBlank()
                ? LongRunTaskUpdateToolSupport.deriveTaskTitle(summary, session.longRunningTaskTitle())
                : input.title().strip();
        String reason = normalizeOptional(input.reason());
        LongRunningTaskMetadata current = store.loadTask(taskId);
        LongRunningTaskMetadata updated = store.updateTaskShell(
                taskId,
                title,
                current.status(),
                reason,
                current.executionStarted(),
                session.sessionId(),
                summary);
        session.setLongRunningTaskTitle(updated.title());
        session.setLongRunningPlanSummary(summary);
        session.setLongRunningReason(reason);
        return succeeded("Updated draft plan summary for " + taskId + ".");
    }

    private ToolResult replaceFeatureList(LongRunningTaskStore store, String taskId, Input input) {
        List<FeatureInput> featureInputs = input.features() == null ? List.of() : input.features();
        Map<String, FeatureItem> existingFeatures = store.readFeatureList(taskId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        FeatureItem::id,
                        feature -> feature,
                        (left, right) -> left,
                        java.util.LinkedHashMap::new));
        List<FeatureItem> features = featureInputs.stream()
                .map(feature -> featureItemForReplacement(feature, existingFeatures.get(feature.id())))
                .toList();
        store.replaceFeatureList(taskId, features);
        return succeeded("Replaced feature list for " + taskId + " (" + features.size() + " feature(s)).");
    }

    private static FeatureItem featureItemForReplacement(FeatureInput feature, FeatureItem existing) {
        boolean canPreservePassedState = existing != null
                && existing.passes()
                && sameFeatureDefinition(feature, existing);
        if (Boolean.TRUE.equals(feature.passes()) && !canPreservePassedState) {
            throw new IllegalArgumentException(
                    "passes=true can only preserve an unchanged already-passed feature: " + feature.id());
        }
        boolean passes = feature.passes() == null ? canPreservePassedState : Boolean.TRUE.equals(feature.passes());
        List<String> evidence = passes ? existing.verificationEvidence() : List.of();
        return new FeatureItem(
                feature.id(),
                feature.category(),
                feature.priority(),
                feature.description(),
                feature.depends_on(),
                feature.verification_steps(),
                passes,
                evidence);
    }

    private static boolean sameFeatureDefinition(FeatureInput feature, FeatureItem existing) {
        return Objects.equals(feature.category(), existing.category())
                && Objects.equals(feature.priority(), existing.priority())
                && Objects.equals(feature.description(), existing.description())
                && Objects.equals(listOrEmpty(feature.depends_on()), existing.dependsOn())
                && Objects.equals(listOrEmpty(feature.verification_steps()), existing.verificationSteps());
    }

    private static List<String> listOrEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    private ToolResult replaceKnownIssues(LongRunningTaskStore store, String taskId, Input input) {
        List<IssueInput> issueInputs = input.issues() == null ? List.of() : input.issues();
        Instant now = Instant.now();
        List<KnownIssue> issues = issueInputs.stream()
                .map(issue -> {
                    String status = issue.status() == null || issue.status().isBlank()
                            ? "open"
                            : issue.status().strip().toLowerCase(Locale.ROOT);
                    return new KnownIssue(
                            issue.id(),
                            issue.description(),
                            issue.severity(),
                            status,
                            issue.discovered_in() == null || issue.discovered_in().isBlank()
                                    ? "DRAFT"
                                    : issue.discovered_in().strip(),
                            issue.verification_steps(),
                            now,
                            "resolved".equals(status) ? now : null);
                })
                .toList();
        store.replaceKnownIssues(taskId, issues);
        return succeeded("Replaced known issues for " + taskId + " (" + issues.size() + " issue(s)).");
    }

    private ToolResult appendProgress(LongRunningTaskStore store, String taskId, Input input) {
        String text = requireNonBlank(input.text(), "text");
        store.appendProgress(taskId, text.endsWith(System.lineSeparator()) ? text : text + System.lineSeparator());
        return succeeded("Progress appended for " + taskId + ".");
    }

    private void appendEvent(
            LongRunningTaskStore store,
            String taskId,
            ConversationSession session,
            String action,
            ToolResult result,
            Input input) {
        try {
            store.appendEvent(taskId, LongRunningTaskEvent.of(
                    "longrun_plan_update",
                    taskId,
                    session.sessionId(),
                    session.longRunningStage() == null ? null : session.longRunningStage().name(),
                    action,
                    result.success(),
                    result.output(),
                    eventDetails(input)));
        } catch (RuntimeException ignored) {
            // Best-effort diagnostics only.
        }
    }

    private static Map<String, String> eventDetails(Input input) {
        java.util.LinkedHashMap<String, String> details = new java.util.LinkedHashMap<>();
        putIfPresent(details, "title", input.title());
        putIfPresent(details, "reason", input.reason());
        if (input.plan_summary() != null) {
            details.put("planSummaryLength", String.valueOf(input.plan_summary().strip().length()));
        }
        if (input.features() != null) {
            details.put("featureCount", String.valueOf(input.features().size()));
        }
        if (input.issues() != null) {
            details.put("issueCount", String.valueOf(input.issues().size()));
        }
        return Map.copyOf(details);
    }

    private static boolean isDraftStage(LongRunningStage stage) {
        return stage == LongRunningStage.DRAFT || stage == LongRunningStage.INTERRUPT;
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

    private static ToolResult succeeded(String output) {
        return new ToolResult("longrun_plan_update", true, output);
    }

    private static ToolResult failed(String output) {
        return new ToolResult("longrun_plan_update", false, output);
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

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isBlank() ? null : stripped;
    }

    private static void putIfPresent(Map<String, String> details, String key, String value) {
        if (value != null && !value.isBlank()) {
            details.put(key, value.strip());
        }
    }

    private static String safe(String value) {
        return value == null ? "(missing)" : value;
    }
}
