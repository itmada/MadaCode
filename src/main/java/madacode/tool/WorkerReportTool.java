package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.core.model.ToolResult;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.longrunning.LongRunningTaskEvent;
import madacode.longrunning.LongRunningTaskStore;
import madacode.longrunning.WorkerReport;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Tool for worker agents to report the outcome of a bounded work cycle.
 *
 * <p>This tool writes a structured report event, appends a handoff entry to
 * progress.txt, and records the report in the session for the launcher to read.
 */
public final class WorkerReportTool implements Tool<WorkerReportTool.Input> {

    public record Input(
            String task_id,
            String status,
            String summary,
            String feature_id,
            String issue_id,
            List<String> files_changed,
            List<String> verification,
            String next) {}

    @Override
    public String name() {
        return "worker_report";
    }

    @Override
    public String description() {
        return "Report the outcome of a bounded worker cycle. Must be called exactly once before the worker session ends. "
                + "Allowed statuses: progress_made, task_completed, blocked, failed, needs_user.";
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
        properties.set("task_id", ToolSchemas.stringProperty(
                mapper, "The task id. Must match the active session task."));
        properties.set("status", ToolSchemas.stringEnumProperty(mapper,
                "Worker outcome status",
                "progress_made", "task_completed", "blocked", "failed", "needs_user"));
        properties.set("summary", ToolSchemas.stringProperty(
                mapper, "Human-readable summary of what was done or what happened."));
        properties.set("feature_id", ToolSchemas.stringProperty(
                mapper, "The feature id that was worked on, if applicable."));
        properties.set("issue_id", ToolSchemas.stringProperty(
                mapper, "The issue id that was worked on, if applicable."));
        properties.set("files_changed", ToolSchemas.arrayProperty(
                mapper, "List of files that were created or modified.", ToolSchemas.stringItem(mapper)));
        properties.set("verification", ToolSchemas.arrayProperty(
                mapper, "Verification steps that were run and their outcomes.", ToolSchemas.stringItem(mapper)));
        properties.set("next", ToolSchemas.stringProperty(
                mapper, "Suggested next action for the launcher or user."));
        return ToolSchemas.objectSchema(mapper, properties, "task_id", "status", "summary");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        Objects.requireNonNull(input, "input");
        ConversationSession session = context.session();

        // Guard: must be a worker session in long-running RUNNING stage.
        if (session.workflowMode() != SessionMode.LONG_RUNNING) {
            return failed("Long-running mode is not active for this session.");
        }
        if (!session.isLongRunningWorkerSession()) {
            return failed("worker_report is only available in a long-running worker session.");
        }
        if (session.longRunningStage() != LongRunningStage.RUNNING) {
            return failed("worker_report is only available in the RUNNING stage. Current stage: "
                    + session.longRunningStage());
        }
        if (session.lastWorkerReport().isPresent()) {
            return failed("worker_report has already been recorded for this worker session.");
        }

        String sessionTaskId = session.longRunningTaskId();
        if (sessionTaskId == null || sessionTaskId.isBlank()) {
            return failed("No initialized long-running task is active for this session.");
        }
        if (input.task_id() == null || !input.task_id().strip().equals(sessionTaskId)) {
            return failed("task_id '" + input.task_id() + "' does not match the active task '" + sessionTaskId + "'.");
        }

        WorkerReport.Status status = WorkerReport.Status.fromWire(input.status());
        if (status == null) {
            return failed("Invalid status '" + input.status() + "'. Allowed: " + WorkerReport.VALID_STATUS_WIRE_VALUES);
        }
        if (input.summary() == null || input.summary().isBlank()) {
            return failed("summary must be non-empty.");
        }

        List<String> filesChanged = input.files_changed() == null ? List.of() : input.files_changed();
        List<String> verification = input.verification() == null ? List.of() : input.verification();

        WorkerReport report = new WorkerReport(
                sessionTaskId,
                session.sessionId(),
                status,
                input.summary().strip(),
                input.feature_id() == null ? null : input.feature_id().strip(),
                input.issue_id() == null ? null : input.issue_id().strip(),
                filesChanged,
                verification,
                input.next() == null ? null : input.next().strip()
        );

        try {
            LongRunningTaskStore store = new LongRunningTaskStore(context.workingDirectory());
            store.validateTaskDirectory(sessionTaskId);
            store.requireRunning(sessionTaskId);

            // Write event
            store.appendEvent(sessionTaskId, LongRunningTaskEvent.of(
                    "worker_report",
                    sessionTaskId,
                    session.sessionId(),
                    session.longRunningStage().name(),
                    status.name(),
                    status != WorkerReport.Status.FAILED,
                    report.summary(),
                    Map.of(
                            "workerSessionId", report.workerSessionId(),
                            "featureId", report.featureId() == null ? "" : report.featureId(),
                            "issueId", report.issueId() == null ? "" : report.issueId(),
                            "filesChanged", String.join(", ", report.filesChanged()),
                            "verification", String.join("; ", report.verification()),
                            "next", report.next() == null ? "" : report.next())));
            store.appendProgress(sessionTaskId, progressEntry(report));

            // Record in session for launcher to read
            session.recordWorkerReport(report);

            return new ToolResult(name(), true,
                    "worker_report recorded: status=" + status.name().toLowerCase(Locale.ROOT)
                            + ", summary=" + report.summary());
        } catch (RuntimeException e) {
            return failed("Failed to write worker report: " + e.getMessage());
        }
    }

    private static ToolResult failed(String message) {
        return new ToolResult("worker_report", false, message);
    }

    private static String progressEntry(WorkerReport report) {
        StringBuilder entry = new StringBuilder();
        entry.append("## ").append(Instant.now()).append(System.lineSeparator());
        entry.append("worker_report: ")
                .append(report.status().name().toLowerCase(Locale.ROOT))
                .append(System.lineSeparator());
        if (report.featureId() != null && !report.featureId().isBlank()) {
            entry.append("feature: ").append(report.featureId()).append(System.lineSeparator());
        }
        if (report.issueId() != null && !report.issueId().isBlank()) {
            entry.append("issue: ").append(report.issueId()).append(System.lineSeparator());
        }
        entry.append("summary: ").append(report.summary()).append(System.lineSeparator());
        if (!report.filesChanged().isEmpty()) {
            entry.append("files_changed: ")
                    .append(String.join(", ", report.filesChanged()))
                    .append(System.lineSeparator());
        }
        if (!report.verification().isEmpty()) {
            entry.append("verification: ")
                    .append(String.join("; ", report.verification()))
                    .append(System.lineSeparator());
        }
        if (report.next() != null && !report.next().isBlank()) {
            entry.append("next: ").append(report.next()).append(System.lineSeparator());
        }
        entry.append(System.lineSeparator());
        return entry.toString();
    }
}
