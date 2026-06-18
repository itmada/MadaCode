package madacode.eval;

import java.time.Instant;

/**
 * Reproducibility metadata recorded with each result. Sensitive provider credentials are
 * deliberately excluded.
 */
public record EvalRunManifest(
        Instant startedAt,
        String caseHash,
        String gitCommit,
        boolean dirtyWorktree,
        String provider,
        String model,
        String runtimeFingerprint,
        String scorerFingerprint,
        String isolation,
        String judgeVisibility,
        String hostAccess,
        String networkAccess,
        boolean trustedMeasurement,
        String javaVersion,
        String os) {
}
