package madacode.longrunning;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class LongRunningTaskStore {

    private final LongRunningTaskRepository repository;
    private final LongRunningEventLog eventLog;
    private final LongRunningLockManager lockManager;

    public LongRunningTaskStore(Path projectDirectory) {
        this(projectDirectory, new ObjectMapper());
    }

    LongRunningTaskStore(Path projectDirectory, ObjectMapper mapper) {
        Objects.requireNonNull(projectDirectory, "projectDirectory");
        Objects.requireNonNull(mapper, "mapper");
        this.repository = new LongRunningTaskRepository(projectDirectory, mapper);
        this.eventLog = new LongRunningEventLog(repository, mapper);
        this.lockManager = new LongRunningLockManager(repository);
        this.repository.attachEventLog(eventLog);
    }

    public static LongRunningTaskMetadata createTask(Path projectDirectory, CreateTaskRequest request) {
        return new LongRunningTaskStore(projectDirectory).createTask(request);
    }

    public synchronized LongRunningTaskMetadata createTask(CreateTaskRequest request) {
        return repository.createTask(request);
    }

    public synchronized LongRunningTaskMetadata loadTask(String taskId) {
        return repository.loadTask(taskId);
    }

    public LongRunningTaskLease acquireExecutionLease(String taskId) {
        return lockManager.acquireExecutionLease(taskId);
    }

    public synchronized LongRunningTaskMetadata updateTaskShell(
            String taskId,
            String title,
            String status,
            String reason,
            Instant executionStarted,
            String controlSessionId,
            String planSummary) {
        return lockManager.withTaskMetadataLock(taskId, () -> repository.updateTaskShell(
                taskId, title, status, reason, executionStarted, controlSessionId, planSummary));
    }

    public synchronized List<FeatureItem> readFeatureList(String taskId) {
        return repository.readFeatureList(taskId);
    }

    public synchronized Optional<String> readPlanSummary(String taskId) {
        return repository.readPlanSummary(taskId);
    }

    public synchronized void writePlanSummary(String taskId, String plan) {
        lockManager.withTaskMetadataLock(taskId, () -> {
            repository.writePlanSummary(taskId, plan);
            return null;
        });
    }

    public synchronized void writeInitialFeatureList(String taskId, List<FeatureItem> features) {
        lockManager.withTaskMetadataLock(taskId, () -> {
            repository.writeInitialFeatureList(taskId, features);
            return null;
        });
    }

    public synchronized void replaceFeatureList(String taskId, List<FeatureItem> features) {
        lockManager.withTaskMetadataLock(taskId, () -> {
            repository.replaceFeatureList(taskId, features);
            return null;
        });
    }

    public synchronized FeatureItem markFeaturePassed(
            String taskId, String featureId, List<String> verificationEvidence) {
        return lockManager.withTaskMetadataLock(
                taskId,
                () -> repository.markFeaturePassed(taskId, featureId, verificationEvidence));
    }

    public synchronized List<KnownIssue> readKnownIssues(String taskId) {
        return repository.readKnownIssues(taskId);
    }

    public synchronized void replaceKnownIssues(String taskId, List<KnownIssue> issues) {
        lockManager.withTaskMetadataLock(taskId, () -> {
            repository.replaceKnownIssues(taskId, issues);
            return null;
        });
    }

    public synchronized void appendEvent(String taskId, LongRunningTaskEvent event) {
        Objects.requireNonNull(event, "event");
        eventLog.appendEvent(taskId, event);
    }

    public synchronized List<LongRunningTaskEvent> readEvents(String taskId) {
        return eventLog.readEvents(taskId);
    }

    public synchronized List<LongRunningTaskEvent> readRecentEvents(String taskId, int limit) {
        return eventLog.readRecentEvents(taskId, limit);
    }

    public synchronized void writeCheckpoint(String taskId, LongRunningWorkspaceCheckpoint checkpoint) {
        repository.writeCheckpoint(taskId, checkpoint);
    }

    public synchronized Optional<LongRunningWorkspaceCheckpoint> readCheckpoint(String taskId) {
        return repository.readCheckpoint(taskId);
    }

    public synchronized KnownIssue recordIssue(String taskId, KnownIssue issue) {
        return lockManager.withTaskMetadataLock(taskId, () -> repository.recordIssue(taskId, issue));
    }

    public synchronized KnownIssue markIssueResolved(String taskId, String issueId) {
        return lockManager.withTaskMetadataLock(taskId, () -> repository.markIssueResolved(taskId, issueId));
    }

    public synchronized KnownIssue updateIssueStatus(String taskId, String issueId, String newStatus) {
        return lockManager.withTaskMetadataLock(
                taskId,
                () -> repository.updateIssueStatus(taskId, issueId, newStatus));
    }

    /** Outcome of recording a failed fix attempt against an issue. */
    public enum IssueFixOutcome {
        /** Below threshold — the issue stays active for another attempt. */
        RETRY,
        /** Threshold reached on an ordinary issue — auto-deferred, no longer blocking. */
        DEFERRED,
        /** Threshold reached on a blocker-severity issue — needs user escalation. */
        ESCALATED
    }

    public synchronized IssueFixOutcome recordIssueFixAttempt(String taskId, String issueId, int threshold) {
        return lockManager.withTaskMetadataLock(
                taskId,
                () -> repository.recordIssueFixAttempt(taskId, issueId, threshold));
    }

    /** Blocking reason if the task may not be marked completed yet, else null. */
    public synchronized String completionBlockReason(String taskId) {
        return repository.completionBlockReason(taskId);
    }

    public synchronized void appendProgress(String taskId, String text) {
        lockManager.withTaskMetadataLock(taskId, () -> {
            repository.appendProgress(taskId, text);
            return null;
        });
    }

    public synchronized String readProgress(String taskId) {
        return repository.readProgress(taskId);
    }

    public synchronized LongRunningTaskMetadata markTaskCompleted(String taskId) {
        return lockManager.withTaskMetadataLock(taskId, () -> repository.markTaskCompleted(taskId));
    }

    public synchronized LongRunningTaskMetadata markTaskExecuting(String taskId) {
        return lockManager.withTaskMetadataLock(taskId, () -> repository.markTaskExecuting(taskId));
    }

    public synchronized LongRunningTaskMetadata markTaskInterrupted(String taskId) {
        return lockManager.withTaskMetadataLock(taskId, () -> repository.markTaskInterrupted(taskId, "user_interrupted"));
    }

    public synchronized LongRunningTaskMetadata markTaskInterrupted(String taskId, String reason) {
        return lockManager.withTaskMetadataLock(taskId, () -> repository.markTaskInterrupted(taskId, reason));
    }

    public synchronized void requireRunning(String taskId) {
        repository.requireRunning(taskId);
    }

    public synchronized LongRunningTaskMetadata cancelTask(String taskId) {
        return lockManager.withTaskMetadataLock(taskId, () -> repository.cancelTask(taskId));
    }

    public synchronized LongRunningTaskMetadata markTaskFailed(String taskId) {
        return lockManager.withTaskMetadataLock(taskId, () -> repository.markTaskFailed(taskId));
    }

    public synchronized Path validateTaskDirectory(String taskId) {
        return repository.validateTaskDirectory(taskId);
    }

    public Path taskDirectoryPath(String taskId) {
        return repository.taskDirectoryPath(taskId);
    }
}
