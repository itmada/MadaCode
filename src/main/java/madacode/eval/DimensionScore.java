package madacode.eval;

import java.util.Objects;

/** Result produced by exactly one dimensional scorer. */
public record DimensionScore(
        Dimension dimension,
        EvalResult.JudgeStatus status,
        boolean gating,
        String detail) {

    public DimensionScore {
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(status, "status");
        detail = detail == null ? "" : detail;
    }

    public boolean passed() {
        return status == EvalResult.JudgeStatus.PASS;
    }
}
