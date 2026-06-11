package madacode.longrunning;

import madacode.core.session.LongRunningWorkerReport;

import java.util.List;
import java.util.Set;

/**
 * Structured report produced by a worker agent at the end of a bounded work cycle.
 *
 * <p>The launcher reads this report to decide whether to continue, stop, or
 * escalate to the user.
 */
public record WorkerReport(
        String taskId,
        String workerSessionId,
        Status status,
        String summary,
        String featureId,
        String issueId,
        List<String> filesChanged,
        List<String> verification,
        String next
) implements LongRunningWorkerReport {

    public enum Status {
        PROGRESS_MADE,
        TASK_COMPLETED,
        BLOCKED,
        FAILED,
        NEEDS_USER;

        public static Status fromWire(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Status.valueOf(value.strip().toUpperCase().replace('-', '_'));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    public static final Set<String> VALID_STATUS_WIRE_VALUES = Set.of(
            "progress_made", "task_completed", "blocked", "failed", "needs_user");
}
