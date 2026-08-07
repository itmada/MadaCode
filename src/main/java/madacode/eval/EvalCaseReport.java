package madacode.eval;

import madacode.core.model.TokenUsage;

import java.util.List;
import java.util.Objects;

/**
 * The aggregate result of running one eval case {@code samples} times.
 *
 * <p>A single attempt against a non-deterministic model is a high-variance coin flip, so the
 * authoritative case-level signal is computed over all attempts:
 * <ul>
 *   <li><b>pass@k</b> — at least one of the {@code k} valid attempts passed.</li>
 *   <li><b>k/N pass rate</b> — passing attempts over <em>valid</em> attempts; attempts that
 *       ended in an infrastructure error are excluded from the denominator so flaky infra
 *       never silently lowers (or, via {@code pass@k}, raises) the score.</li>
 * </ul>
 *
 * <p>The exploratory case-level {@link PassAtKVerdict} is {@code INFRA_ERROR} only when
 * <em>every</em> attempt was an infrastructure error (nothing was actually measured);
 * otherwise it is {@code PASS_AT_K} when pass@k holds and {@code FAIL} when no valid attempt
 * passed. {@link #stable()} is the stricter promotion/CI signal: every attempt must be a
 * measured pass, with no infrastructure errors hidden from the denominator.
 */
public record EvalCaseReport(
        String id,
        String mode,
        List<String> capabilities,
        List<EvalResult> attempts,
        SkipReason skipReason,
        String skipDetail,
        EvalRunManifest skippedManifest,
        int configuredSamples) {

    public EvalCaseReport(
            String id,
            String mode,
            List<String> capabilities,
            List<EvalResult> attempts) {
        this(id, mode, capabilities, attempts, null, "", null,
                attempts == null ? 0 : attempts.size());
    }

    public EvalCaseReport {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mode, "mode");
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        attempts = attempts == null ? List.of() : List.copyOf(attempts);
        skipDetail = skipDetail == null ? "" : skipDetail;
        if (skipReason == null && attempts.isEmpty()) {
            throw new IllegalArgumentException("case " + id + ": at least one attempt is required");
        }
        if (skipReason != null) {
            if (!attempts.isEmpty()) {
                throw new IllegalArgumentException("case " + id + ": skipped reports must not contain attempts");
            }
            Objects.requireNonNull(skippedManifest, "skippedManifest");
            if (configuredSamples <= 0) {
                throw new IllegalArgumentException("case " + id + ": configuredSamples must be positive");
            }
        }
    }

    /** Builds a report from the attempts of a single case, deriving identity from the first. */
    public static EvalCaseReport of(List<EvalResult> attempts) {
        EvalResult first = attempts.getFirst();
        return new EvalCaseReport(first.id(), first.mode(), first.capabilities(), attempts);
    }

    public static EvalCaseReport skipped(
            EvalCaseLoader.LoadedCase loaded,
            EvalRunManifest manifest,
            SkipReason reason,
            String detail) {
        EvalCase evalCase = loaded.evalCase();
        return new EvalCaseReport(
                evalCase.id(),
                evalCase.mode(),
                evalCase.capabilities(),
                List.of(),
                Objects.requireNonNull(reason, "reason"),
                detail,
                manifest,
                evalCase.samplesOrDefault());
    }

    public boolean skipped() {
        return skipReason != null;
    }

    public int samples() {
        return skipped() ? configuredSamples : attempts.size();
    }

    /** Attempts that produced a trustworthy capability measurement (not an infra error). */
    public long validAttempts() {
        if (skipped()) {
            return 0;
        }
        return attempts.stream()
                .filter(a -> a.verdict() != EvalResult.FinalVerdict.INFRA_ERROR)
                .count();
    }

    public long infraErrors() {
        if (skipped()) {
            return 0;
        }
        return attempts.stream()
                .filter(a -> a.verdict() == EvalResult.FinalVerdict.INFRA_ERROR)
                .count();
    }

    /** Number of attempts that passed (k). */
    public long passes() {
        if (skipped()) {
            return 0;
        }
        return attempts.stream().filter(EvalResult::passed).count();
    }

    /** pass@k: at least one valid attempt passed. */
    public boolean passAtK() {
        if (skipped()) {
            return false;
        }
        return attempts.stream().anyMatch(EvalResult::passed);
    }

    /** Strict stability signal: every configured attempt completed as a measured pass. */
    public boolean stable() {
        if (skipped()) {
            return false;
        }
        return infraErrors() == 0 && attempts.stream().allMatch(EvalResult::passed);
    }

    /** True when at least one attempt did not produce a trustworthy measurement. */
    public boolean hasInfraError() {
        return infraErrors() > 0;
    }

    /** k/N pass rate over valid attempts (0 when nothing was validly measured). */
    public double passRate() {
        long valid = validAttempts();
        return valid == 0 ? 0.0 : (double) passes() / valid;
    }

    public WilsonInterval passRateWilson95() {
        return WilsonInterval.of(passes(), validAttempts());
    }

    public PassAtKVerdict passAtKVerdict() {
        if (skipped()) {
            return PassAtKVerdict.SKIPPED;
        }
        if (validAttempts() == 0) {
            return PassAtKVerdict.INFRA_ERROR;
        }
        return passAtK() ? PassAtKVerdict.PASS_AT_K : PassAtKVerdict.FAIL;
    }

    public GateVerdict gateVerdict() {
        if (skipped()) {
            return GateVerdict.SKIPPED;
        }
        if (validAttempts() == 0) {
            return GateVerdict.INFRA_ERROR;
        }
        return stable() ? GateVerdict.PASS : GateVerdict.FAIL;
    }

    /** Case-level exploratory pass means at least one valid attempt passed (pass@k). */
    public boolean passed() {
        return passAtKVerdict() == PassAtKVerdict.PASS_AT_K;
    }

    /** Summed model/tool/token cost across all attempts (cost is cumulative, not averaged). */
    public RunMetrics totalMetrics() {
        if (skipped()) {
            return RunMetrics.ZERO;
        }
        RunMetrics total = RunMetrics.ZERO;
        for (EvalResult a : attempts) {
            total = total.plus(a.metrics() == null ? RunMetrics.ZERO : a.metrics());
        }
        return total;
    }

    public TokenUsage totalTokens() {
        return totalMetrics().tokenUsage();
    }

    public long totalDurationMs() {
        if (skipped()) {
            return 0;
        }
        return attempts.stream().mapToLong(EvalResult::durationMs).sum();
    }

    /** The manifest is identical across attempts (shared runtime); the first is representative. */
    public EvalRunManifest manifest() {
        if (skipped()) {
            return skippedManifest;
        }
        return attempts.getFirst().manifest();
    }

    public enum SkipReason {
        BUDGET,
        AGENT_INCOMPATIBLE
    }

    public enum PassAtKVerdict {
        PASS_AT_K,
        FAIL,
        INFRA_ERROR,
        SKIPPED
    }

    public enum GateVerdict {
        PASS,
        FAIL,
        INFRA_ERROR,
        SKIPPED
    }
}
