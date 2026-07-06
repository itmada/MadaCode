package madacode.eval;

import java.util.List;

record EvalBackendManifest(
        String executionBackend,
        String containerImage,
        String containerImageDigest,
        String resourceLimits,
        String networkPolicy,
        String providerConfigMaterialization,
        List<String> projectExtensionMounts) {

    EvalBackendManifest {
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

    static EvalBackendManifest localUnsafe() {
        return new EvalBackendManifest(
                "local",
                "",
                "",
                "",
                "local-unsafe",
                "",
                List.of());
    }

    static EvalBackendManifest dockerPhase1(
            String image,
            String imageDigest,
            String resourceLimits,
            String providerConfigMaterialization,
            List<String> projectExtensionMounts) {
        return new EvalBackendManifest(
                "docker",
                image,
                imageDigest,
                resourceLimits,
                "agent=default-bridge;verify=none;egress=unobserved",
                providerConfigMaterialization,
                projectExtensionMounts);
    }

    static EvalBackendManifest dockerPhase2(
            String image,
            String imageDigest,
            String resourceLimits,
            String providerConfigMaterialization,
            List<String> projectExtensionMounts) {
        return new EvalBackendManifest(
                "docker",
                image,
                imageDigest,
                resourceLimits,
                "agent=internal-network;egress=allowlist-proxy;verify=none;egressReport=observed",
                providerConfigMaterialization,
                projectExtensionMounts);
    }
}
