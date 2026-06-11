package madacode.core.session;

import java.util.Optional;

/**
 * Mutable long-running workflow state attached to a conversation session.
 */
public final class LongRunningSessionState {

    private volatile LongRunningStage stage;
    private volatile String taskId;
    private volatile String taskDirectory;
    private volatile String title;
    private volatile String reason;
    private volatile String planSummary;
    private volatile boolean workerSession;
    private volatile LongRunningTransitionRequest pendingTransitionRequest;
    private volatile LongRunningWorkerReport lastWorkerReport;

    public boolean isActive() {
        return stage != null;
    }

    public LongRunningStage stage() {
        return stage;
    }

    public void setStage(LongRunningStage stage) {
        this.stage = stage;
    }

    public String taskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String taskDirectory() {
        return taskDirectory;
    }

    public void setTaskDirectory(String taskDirectory) {
        this.taskDirectory = taskDirectory;
    }

    public String title() {
        return title;
    }

    public void setTitle(String title) {
        this.title = normalizeOptionalText(title);
    }

    public String reason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = normalizeOptionalText(reason);
    }

    public String planSummary() {
        return planSummary;
    }

    public void setPlanSummary(String planSummary) {
        this.planSummary = normalizeOptionalText(planSummary);
    }

    public boolean isWorkerSession() {
        return workerSession;
    }

    public void setWorkerSession(boolean workerSession) {
        this.workerSession = workerSession;
    }

    public Optional<LongRunningTransitionRequest> pendingTransitionRequest() {
        return Optional.ofNullable(pendingTransitionRequest);
    }

    public void setPendingTransitionRequest(LongRunningTransitionRequest pendingTransitionRequest) {
        this.pendingTransitionRequest = pendingTransitionRequest;
    }

    public void clearPendingTransitionRequest() {
        this.pendingTransitionRequest = null;
    }

    public Optional<LongRunningWorkerReport> lastWorkerReport() {
        return Optional.ofNullable(lastWorkerReport);
    }

    public void recordWorkerReport(LongRunningWorkerReport report) {
        this.lastWorkerReport = report;
    }

    public void clearWorkerReport() {
        this.lastWorkerReport = null;
    }

    static void requireLongRunningMode(
            SessionMode workflowMode,
            String fieldName,
            Object value) {
        if (value != null && workflowMode != SessionMode.LONG_RUNNING) {
            throw new IllegalStateException(
                    fieldName + " requires workflowMode LONG_RUNNING");
        }
    }

    static String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isBlank() ? null : normalized;
    }
}
