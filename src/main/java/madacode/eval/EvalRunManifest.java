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
        String agent,
        String javaVersion,
        String os,
        String caseRepository,
        String caseBaseCommit,
        String workspaceProtocol) {

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
        agent = agent == null || agent.isBlank() ? "madacode" : agent;
        caseRepository = caseRepository == null ? "" : caseRepository;
        caseBaseCommit = caseBaseCommit == null ? "" : caseBaseCommit;
        workspaceProtocol = workspaceProtocol == null || workspaceProtocol.isBlank()
                ? "workspace-copy"
                : workspaceProtocol;
    }

    /** Compatibility constructor for reports written before case source metadata was recorded. */
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
            String executionBackend,
            String containerImage,
            String containerImageDigest,
            String resourceLimits,
            String networkPolicy,
            String providerConfigMaterialization,
            List<String> projectExtensionMounts,
            String agent,
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
                executionBackend,
                containerImage,
                containerImageDigest,
                resourceLimits,
                networkPolicy,
                providerConfigMaterialization,
                projectExtensionMounts,
                agent,
                javaVersion,
                os,
                "",
                "",
                "workspace-copy");
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
                "madacode",
                javaVersion,
                os,
                "",
                "",
                "workspace-copy");
    }
}
