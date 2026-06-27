package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.core.model.ToolResult;
import madacode.core.session.ConversationSession;
import madacode.core.session.SessionMode;
import madacode.longrunning.FeatureItem;
import madacode.longrunning.KnownIssue;
import madacode.longrunning.LongRunningTaskEvent;
import madacode.longrunning.LongRunningTaskMetadata;
import madacode.longrunning.LongRunningTaskStore;
import madacode.longrunning.LongRunningWorkspaceCheckpoint;
import madacode.tool.schema.OptionalSchemaProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class LongRunEnvironmentReadTool implements Tool<LongRunEnvironmentReadTool.Input> {

    private static final int DEFAULT_EVENT_LIMIT = 120;
    private static final int MAX_EVENT_LIMIT = 1_000;

    public record Input(
            String view,
            @OptionalSchemaProperty
            String task_id,
            @OptionalSchemaProperty
            Integer event_limit,
            @OptionalSchemaProperty
            Boolean include_events) {}

    @Override
    public String name() {
        return ToolNames.LONGRUN_ENVIRONMENT_READ;
    }

    @Override
    public String description() {
        return "Read the active long-running environment through the task store. "
                + "Use this instead of cat/ls/file_read on .mada/long-running. "
                + "It returns clear, complete model-readable content for task metadata, plan summary, features, "
                + "known issues, progress, checkpoint, and recent events.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("view", ToolSchemas.stringEnumProperty(
                mapper,
                "What to read. Use snapshot when rebuilding task context; use events/progress for focused debugging.",
                "snapshot", "task", "features", "known_issues", "progress", "events", "checkpoint"));
        properties.set("task_id", ToolSchemas.stringProperty(
                mapper, "Optional task id. If present, it must match the active long-running session task."));
        properties.set("event_limit", ToolSchemas.integerProperty(
                mapper, "Maximum recent events to include for snapshot/events views.", 1, MAX_EVENT_LIMIT));
        properties.set("include_events", ToolSchemas.booleanProperty(
                mapper, "For snapshot only: include recent events. Defaults to true."));
        return ToolSchemas.objectSchema(mapper, properties, "view");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        Objects.requireNonNull(input, "input");
        ConversationSession session = context.session();
        if (session.workflowMode() != SessionMode.LONG_RUNNING) {
            return failed("Long-running mode is not active for this session.");
        }
        String activeTaskId;
        try {
            activeTaskId = LongRunEnvironmentSupport.activeTaskId(input.task_id(), session);
        } catch (RuntimeException exception) {
            return failed(safeMessage(exception));
        }
        if (activeTaskId == null || activeTaskId.isBlank()) {
            return succeeded("""
                    Long-running environment is not initialized yet.

                    The Controller should keep discussing the task with the user. After the user confirms the plan is ready to execute, call longrun_environment_update with action=initialize_environment, including a complete plan summary, feature list, known issues list, and initialization progress note.
                    """.strip());
        }

        try {
            LongRunningTaskStore store = new LongRunningTaskStore(context.workingDirectory());
            store.validateTaskDirectory(activeTaskId);
            session.setLongRunningTaskDirectory(store.taskDirectoryPath(activeTaskId).toString());
            String view = input.view() == null ? "snapshot" : input.view().strip().toLowerCase(java.util.Locale.ROOT);
            int eventLimit = eventLimit(input.event_limit());
            String output = switch (view) {
                case "snapshot" -> snapshot(store, activeTaskId, eventLimit, includeEvents(input.include_events()));
                case "task" -> taskSection(store.loadTask(activeTaskId), store.taskDirectoryPath(activeTaskId).toString());
                case "features" -> featuresSection(store.readFeatureList(activeTaskId));
                case "known_issues" -> knownIssuesSection(store.readKnownIssues(activeTaskId));
                case "progress" -> progressSection(store.readProgress(activeTaskId));
                case "events" -> eventsSection(store.readRecentEvents(activeTaskId, eventLimit));
                case "checkpoint" -> checkpointSection(store.readCheckpoint(activeTaskId).orElse(null));
                default -> throw new IllegalArgumentException("Unsupported view: " + input.view());
            };
            return succeeded(output);
        } catch (RuntimeException exception) {
            return failed(safeMessage(exception));
        }
    }

    private static String snapshot(
            LongRunningTaskStore store,
            String taskId,
            int eventLimit,
            boolean includeEvents) {
        StringBuilder out = new StringBuilder();
        out.append("Long-running environment snapshot\n\n");
        out.append(taskSection(store.loadTask(taskId), store.taskDirectoryPath(taskId).toString())).append("\n\n");
        out.append(featuresSection(store.readFeatureList(taskId))).append("\n\n");
        out.append(knownIssuesSection(store.readKnownIssues(taskId))).append("\n\n");
        out.append(progressSection(store.readProgress(taskId))).append("\n\n");
        out.append(checkpointSection(store.readCheckpoint(taskId).orElse(null)));
        if (includeEvents) {
            out.append("\n\n").append(eventsSection(store.readRecentEvents(taskId, eventLimit)));
        }
        return out.toString();
    }

    private static String taskSection(LongRunningTaskMetadata task, String taskDirectory) {
        return """
                task:
                  id: %s
                  title: %s
                  status: %s
                  reason: %s
                  execution_started: %s
                  created_at: %s
                  updated_at: %s
                  control_session_id: %s
                  task_directory: %s
                plan_summary:
                %s
                """.formatted(
                task.id(),
                safe(task.title()),
                safe(task.status()),
                safe(task.reason()),
                instant(task.executionStarted()),
                instant(task.createdAt()),
                instant(task.updatedAt()),
                safe(task.controlSessionId()),
                taskDirectory,
                block(task.planSummary()));
    }

    private static String featuresSection(List<FeatureItem> features) {
        StringBuilder out = new StringBuilder("features (" + features.size() + "):");
        if (features.isEmpty()) {
            return out.append("\n  (none)").toString();
        }
        for (FeatureItem feature : features) {
            out.append("\n- id: ").append(feature.id())
                    .append("\n  category: ").append(safe(feature.category()))
                    .append("\n  priority: ").append(safe(feature.priority()))
                    .append("\n  passes: ").append(feature.passes())
                    .append("\n  description: ").append(safe(feature.description()))
                    .append("\n  depends_on: ").append(feature.dependsOn())
                    .append("\n  verification_steps: ").append(feature.verificationSteps())
                    .append("\n  verification_evidence: ").append(feature.verificationEvidence());
        }
        return out.toString();
    }

    private static String knownIssuesSection(List<KnownIssue> issues) {
        StringBuilder out = new StringBuilder("known_issues (" + issues.size() + "):");
        if (issues.isEmpty()) {
            return out.append("\n  (none)").toString();
        }
        for (KnownIssue issue : issues) {
            out.append("\n- id: ").append(issue.id())
                    .append("\n  severity: ").append(safe(issue.severity()))
                    .append("\n  status: ").append(safe(issue.status()))
                    .append("\n  attempts: ").append(issue.attempts())
                    .append("\n  description: ").append(safe(issue.description()))
                    .append("\n  discovered_in: ").append(safe(issue.discoveredIn()))
                    .append("\n  verification_steps: ").append(issue.verificationSteps())
                    .append("\n  created_at: ").append(instant(issue.createdAt()))
                    .append("\n  resolved_at: ").append(instant(issue.resolvedAt()));
        }
        return out.toString();
    }

    private static String progressSection(String progress) {
        return "progress:\n" + block(progress);
    }

    private static String checkpointSection(LongRunningWorkspaceCheckpoint checkpoint) {
        if (checkpoint == null) {
            return "checkpoint:\n  (none)";
        }
        return """
                checkpoint:
                  captured_at: %s
                  project_directory: %s
                  git_repository: %s
                  git_root: %s
                  branch: %s
                  head: %s
                  dirty: %s
                  status_short:
                %s
                """.formatted(
                instant(checkpoint.capturedAt()),
                checkpoint.projectDirectory(),
                checkpoint.gitRepository(),
                checkpoint.gitRoot(),
                safe(checkpoint.branch()),
                safe(checkpoint.head()),
                checkpoint.dirty(),
                block(checkpoint.statusShort()));
    }

    private static String eventsSection(List<LongRunningTaskEvent> events) {
        StringBuilder out = new StringBuilder("recent_events (" + events.size() + "):");
        if (events.isEmpty()) {
            return out.append("\n  (none)").toString();
        }
        for (LongRunningTaskEvent event : events) {
            out.append("\n- timestamp: ").append(instant(event.timestamp()))
                    .append("\n  type: ").append(event.type())
                    .append("\n  stage: ").append(safe(event.stage()))
                    .append("\n  action: ").append(safe(event.action()))
                    .append("\n  success: ").append(event.success())
                    .append("\n  message: ").append(safe(event.message()));
            if (!event.details().isEmpty()) {
                out.append("\n  details:");
                for (Map.Entry<String, String> detail : event.details().entrySet()) {
                    out.append("\n    ").append(detail.getKey()).append(": ").append(safe(detail.getValue()));
                }
            }
        }
        return out.toString();
    }

    private static int eventLimit(Integer value) {
        if (value == null) {
            return DEFAULT_EVENT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_EVENT_LIMIT, value));
    }

    private static boolean includeEvents(Boolean value) {
        return value == null || value;
    }

    private static String block(String value) {
        if (value == null || value.isBlank()) {
            return "  (empty)";
        }
        return value.stripTrailing()
                .lines()
                .map(line -> "  " + line)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "(none)" : value.replaceAll("\\s+", " ");
    }

    private static String instant(Instant value) {
        return value == null ? "(none)" : value.toString();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    private static ToolResult succeeded(String output) {
        return new ToolResult(ToolNames.LONGRUN_ENVIRONMENT_READ, true, output);
    }

    private static ToolResult failed(String output) {
        return new ToolResult(ToolNames.LONGRUN_ENVIRONMENT_READ, false, output);
    }
}
