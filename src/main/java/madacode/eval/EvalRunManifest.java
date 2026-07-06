package madacode.eval;

import java.time.Instant;
import java.util.List;

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
        String executionBackend,
        String containerImage,
        String containerImageDigest,
        String resourceLimits,
        String networkPolicy,
        String providerConfigMaterialization,
        List<String> projectExtensionMounts,
        String javaVersion,
        String os) {

    public EvalRunManifest {
        executionBackend = executionBackend == null || executionBackend.isBlank()
                ? "local"
                : executionBackend;
        containerImage = containerImage == null ? "" : containerImage;
        containerImageDigest = containerImageDigest == null ? "" : containerImageDigest;
        resourceLimits = resourceLimits == null ? "" : resourceLimits;
        networkPolicy = networkPolicy == null ? "" : networkPolicy;
        providerConfigMaterialization = providerConfigMaterialization == null
                ? ""
                : providerConfigMaterialization;
        projectExtensionMounts = projectExtensionMounts == null ? List.of() : List.copyOf(projectExtensionMounts);
    }

    public EvalRunManifest(
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
        this(
                startedAt,
                caseHash,
                gitCommit,
                dirtyWorktree,
                provider,
                model,
                runtimeFingerprint,
                scorerFingerprint,
                isolation,
                judgeVisibility,
                hostAccess,
                networkAccess,
                trustedMeasurement,
                "local",
                "",
                "",
                "",
                "local-unsafe",
                "",
                List.of(),
                javaVersion,
                os);
    }
}
