package madacode.eval;

import java.time.Instant;

/** Lifecycle metadata for a root eval report while cases are still being checkpointed. */
public record EvalRunProgress(
        Status status,
        int plannedCases,
        int completedCases,
        Instant startedAt,
        Instant updatedAt,
        String currentCaseId,
        String abortDetail) {

    public EvalRunProgress {
        status = status == null ? Status.RUNNING : status;
        if (plannedCases < 0 || completedCases < 0 || completedCases > plannedCases) {
            throw new IllegalArgumentException("invalid eval run progress: "
                    + completedCases + "/" + plannedCases);
        }
        startedAt = startedAt == null ? Instant.now() : startedAt;
        updatedAt = updatedAt == null ? startedAt : updatedAt;
        currentCaseId = blankToNull(currentCaseId);
        abortDetail = blankToNull(abortDetail);
    }

    public static EvalRunProgress completed(int cases) {
        Instant now = Instant.now();
        return new EvalRunProgress(Status.COMPLETED, cases, cases, now, now, null, null);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public enum Status {
        RUNNING,
        COMPLETED,
        ABORTED
    }
}
