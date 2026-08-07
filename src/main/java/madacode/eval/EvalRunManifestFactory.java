package madacode.eval;

import madacode.bootstrap.HeadlessAgentRuntime;
import madacode.governance.IsolationProfile;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

final class EvalRunManifestFactory {

    private EvalRunManifestFactory() {
    }

    static EvalRunManifest capture(
            Path projectDir,
            EvalCaseLoader.LoadedCase loaded,
            HeadlessAgentRuntime runtime,
            IsolationProfile isolationProfile,
            String scorerFingerprint,
            Instant startedAt) {
        return capture(
                projectDir,
                loaded,
                runtime,
                isolationProfile,
                scorerFingerprint,
                startedAt,
                EvalAgent.MADACODE);
    }

    static EvalRunManifest capture(
            Path projectDir,
            EvalCaseLoader.LoadedCase loaded,
            HeadlessAgentRuntime runtime,
            IsolationProfile isolationProfile,
            String scorerFingerprint,
            Instant startedAt,
            EvalAgent agent) {
        return capture(
                projectDir,
                loaded,
                runtime,
                isolationProfile,
                scorerFingerprint,
                startedAt,
                EvalBackendManifest.localUnsafe(),
                agent);
    }

    static EvalRunManifest capture(
            Path projectDir,
            EvalCaseLoader.LoadedCase loaded,
            HeadlessAgentRuntime runtime,
            IsolationProfile isolationProfile,
            String scorerFingerprint,
            Instant startedAt,
            EvalBackendManifest backend) {
        return capture(
                projectDir,
                loaded,
                runtime,
                isolationProfile,
                scorerFingerprint,
                startedAt,
                backend,
                EvalAgent.MADACODE);
    }

    static EvalRunManifest capture(
            Path projectDir,
            EvalCaseLoader.LoadedCase loaded,
            HeadlessAgentRuntime runtime,
            IsolationProfile isolationProfile,
            String scorerFingerprint,
            Instant startedAt,
            EvalBackendManifest backend,
            EvalAgent agent) {
        GitState git = gitState(projectDir);
        EvalBackendManifest safeBackend = backend == null ? EvalBackendManifest.localUnsafe() : backend;
        EvalAgent safeAgent = agent == null ? EvalAgent.MADACODE : agent;
        return new EvalRunManifest(
                startedAt,
                loaded.caseHash(),
                git.commit(),
                git.dirty(),
                runtime == null ? "(none)" : runtime.providerName(),
                runtime == null ? "(none)" : runtime.modelName(),
                runtime == null ? "(none)" : runtime.runtimeFingerprint(),
                scorerFingerprint == null ? "(none)" : scorerFingerprint,
                isolationProfile.level().name(),
                isolationProfile.judgeVisibility().name(),
                isolationProfile.hostAccess().name(),
                isolationProfile.networkAccess().name(),
                isolationProfile.trustedMeasurement(),
                safeBackend.executionBackend(),
                safeBackend.containerImage(),
                safeBackend.containerImageDigest(),
                safeBackend.resourceLimits(),
                safeBackend.networkPolicy(),
                safeBackend.providerConfigMaterialization(),
                safeBackend.projectExtensionMounts(),
                safeAgent.name().toLowerCase(java.util.Locale.ROOT),
                System.getProperty("java.version", "(unknown)"),
                System.getProperty("os.name", "(unknown)") + " "
                        + System.getProperty("os.version", ""),
                loaded.evalCase().repository(),
                loaded.evalCase().baseCommit(),
                workspaceProtocol(loaded.evalCase(), safeBackend));
    }

    private static String workspaceProtocol(EvalCase evalCase, EvalBackendManifest backend) {
        if (!evalCase.hasGitBaseline()) {
            return "workspace-copy";
        }
        return "local".equals(backend.executionBackend())
                ? "git-worktree-clean-judge"
                : "workspace-copy";
    }

    private static GitState gitState(Path projectDir) {
        ProcessSupervisor supervisor = new ProcessSupervisor();
        ProcessSupervisor.Outcome commit = supervisor.run(
                List.of("git", "rev-parse", "HEAD"), projectDir, Duration.ofSeconds(3), 4096);
        ProcessSupervisor.Outcome status = supervisor.run(
                List.of("git", "status", "--porcelain"), projectDir, Duration.ofSeconds(3), 64 * 1024);
        String commitId = commit.succeeded() ? commit.output().strip() : "(unknown)";
        boolean dirty = !status.succeeded() || !status.output().isBlank();
        return new GitState(commitId, dirty);
    }

    private record GitState(String commit, boolean dirty) {
    }
}
