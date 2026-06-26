package madacode.tool;

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
import java.util.List;
import java.util.Map;
import java.util.Objects;

abstract class LongRunPlanUpdateSupport<I> implements Tool<I> {

    record FeatureInput(
            String id,
            String category,
            String priority,
            String description,
            @OptionalSchemaProperty
            List<String> depends_on,
            @OptionalSchemaProperty
            List<String> verification_steps,
            @OptionalSchemaProperty
            Boolean passes) {}

    record IssueInput(
            String id,
            String description,
            String severity,
            @OptionalSchemaProperty
            String status,
            @OptionalSchemaProperty
            String discovered_in,
            @OptionalSchemaProperty
            List<String> verification_steps) {}

    @Override
    public final boolean isReadOnly() {
        return false;
    }

    @Override
    public final boolean isPlanModeSafe() {
        return false;
    }

    @Override
    public final ToolResult execute(I input, ToolUseContext context) {
        Objects.requireNonNull(input, "input");
        ConversationSession session = context.session();
        if (session.workflowMode() != SessionMode.LONG_RUNNING) {
            return failed("Long-running mode is not active for this session.");
        }
        if (session.isLongRunningWorkerSession()) {
            return failed(name() + " is only available in the control session.");
        }
        if (!isDraftStage(session.longRunningStage())) {
            return failed(name() + " is only available while the task is in DRAFT or INTERRUPT. Current stage: "
                    + session.longRunningStage());
        }
        LongRunningTaskStore store = new LongRunningTaskStore(context.workingDirectory());
        ensurePlanningTaskIfNeeded(store, session);
        String taskId = activeTaskId(taskId(input), session);
        try {
            store.validateTaskDirectory(taskId);
            LongRunningTaskMetadata currentTask = store.loadTask(taskId);
            LongRunningStage durableStage = LongRunningStage.fromWire(currentTask.status())
                    .orElseThrow(() -> new IllegalStateException(
                            "Unsupported task status: " + currentTask.status()));
            if (!isDraftStage(durableStage)) {
                return failed(name() + " is only available while the durable task is DRAFT or INTERRUPT. "
                        + "Current task status: " + currentTask.status());
            }
            session.setLongRunningTaskDirectory(store.taskDirectoryPath(taskId).toString());

            ToolResult result = apply(store, taskId, input, session);
            appendEvent(store, taskId, session, actionName(), result, eventDetails(input));
            return result;
        } catch (RuntimeException exception) {
            ToolResult result = failed(exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage());
            appendEvent(store, taskId, session, actionName(), result, eventDetails(input));
            return result;
        }
    }

    protected abstract String actionName();

    protected abstract String taskId(I input);

    protected abstract ToolResult apply(
            LongRunningTaskStore store,
            String taskId,
            I input,
            ConversationSession session);

    protected Map<String, String> eventDetails(I input) {
        return Map.of();
    }

    private static void ensurePlanningTaskIfNeeded(LongRunningTaskStore store, ConversationSession session) {
        if (session.longRunningTaskId() != null && !session.longRunningTaskId().isBlank()) {
            return;
        }
        LongRunningTaskInitializer initializer = new LongRunningTaskInitializer(
                store,
                LongRunningTaskInitializer.TaskIdGenerator::defaultNewTaskId);
        initializer.ensurePlanningTask(session, "");
    }

    protected final ToolResult updatePlanSummary(
            LongRunningTaskStore store,
            String taskId,
            String title,
            String reason,
            String planSummary,
            ConversationSession session) {
        String summary = requireNonBlank(planSummary, "plan_summary");
        String effectiveTitle = title == null || title.isBlank()
                ? LongRunTaskUpdateToolSupport.deriveTaskTitle(summary, session.longRunningTaskTitle())
                : title.strip();
        String normalizedReason = normalizeOptional(reason);
        LongRunningTaskMetadata current = store.loadTask(taskId);
        LongRunningTaskMetadata updated = store.updateTaskShell(
                taskId,
                effectiveTitle,
                current.status(),
                normalizedReason,
                current.executionStarted(),
                session.sessionId(),
                summary);
        session.setLongRunningTaskTitle(updated.title());
        session.setLongRunningPlanSummary(summary);
        session.setLongRunningReason(normalizedReason);
        return succeeded("Updated draft plan summary for " + taskId + ".");
    }

    protected final ToolResult replaceFeatureList(
            LongRunningTaskStore store,
            String taskId,
            List<FeatureInput> featureInputs) {
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

    protected final ToolResult replaceKnownIssues(
            LongRunningTaskStore store,
            String taskId,
            List<IssueInput> issueInputs) {
        Instant now = Instant.now();
        List<KnownIssue> issues = issueInputs.stream()
                .map(issue -> {
                    String status = issue.status() == null || issue.status().isBlank()
                            ? "open"
                            : issue.status().strip().toLowerCase(java.util.Locale.ROOT);
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

    protected final ToolResult appendProgress(LongRunningTaskStore store, String taskId, String text) {
        String value = requireNonBlank(text, "text");
        store.appendProgress(taskId, value.endsWith(System.lineSeparator()) ? value : value + System.lineSeparator());
        return succeeded("Progress appended for " + taskId + ".");
    }

    protected final ToolResult succeeded(String output) {
        return new ToolResult(name(), true, output);
    }

    protected final ToolResult failed(String output) {
        return new ToolResult(name(), false, output);
    }

    protected static String requireNonBlank(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String stripped = value.strip();
        if (stripped.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return stripped;
    }

    protected static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isBlank() ? null : stripped;
    }

    protected static void putIfPresent(Map<String, String> details, String key, String value) {
        if (value != null && !value.isBlank()) {
            details.put(key, value.strip());
        }
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

    private void appendEvent(
            LongRunningTaskStore store,
            String taskId,
            ConversationSession session,
            String action,
            ToolResult result,
            Map<String, String> details) {
        try {
            store.appendEvent(taskId, LongRunningTaskEvent.of(
                    name(),
                    taskId,
                    session.sessionId(),
                    session.longRunningStage() == null ? null : session.longRunningStage().name(),
                    action,
                    result.success(),
                    result.output(),
                    details));
        } catch (RuntimeException ignored) {
            // Best-effort diagnostics only.
        }
    }

    private static boolean isDraftStage(LongRunningStage stage) {
        return stage == LongRunningStage.DRAFT || stage == LongRunningStage.INTERRUPT;
    }

    private static String activeTaskId(String requestedTaskId, ConversationSession session) {
        String active = session.longRunningTaskId();
        if (requestedTaskId == null || requestedTaskId.isBlank()) {
            return active;
        }
        String requested = requestedTaskId.strip();
        if (!requested.equals(active)) {
            throw new IllegalArgumentException("task_id does not match the active long-running task.");
        }
        return active;
    }
}
