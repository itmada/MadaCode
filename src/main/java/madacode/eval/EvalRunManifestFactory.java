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
        GitState git = gitState(projectDir);
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
                System.getProperty("java.version", "(unknown)"),
                System.getProperty("os.name", "(unknown)") + " "
                        + System.getProperty("os.version", ""));
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
