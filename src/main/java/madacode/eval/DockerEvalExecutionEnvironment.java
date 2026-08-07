package madacode.eval;

import madacode.governance.EgressReport;
import madacode.governance.IsolationProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Docker-backed workspace and verify runner for the eval container backend. */
final class DockerEvalExecutionEnvironment implements EvalExecutionEnvironment {

    private final Path workspace;
    private final Path verifyScript;
    private final String dockerCommand;
    private final String image;
    private final List<String> resourceArgs;
    private final IsolationProfile isolationProfile;
    private final DockerEgressProxySession egressProxy;

    DockerEvalExecutionEnvironment(
            Path workspace,
            Path verifyScript,
            String dockerCommand,
            String image,
            List<String> resourceArgs) {
        this(workspace, verifyScript, dockerCommand, image, resourceArgs, null);
    }

    DockerEvalExecutionEnvironment(
            Path workspace,
            Path verifyScript,
            String dockerCommand,
            String image,
            List<String> resourceArgs,
            DockerEgressProxySession egressProxy) {
        this.workspace = workspace;
        this.verifyScript = verifyScript != null && Files.isRegularFile(verifyScript) ? verifyScript : null;
        this.dockerCommand = dockerCommand;
        this.image = image;
        this.resourceArgs = resourceArgs == null ? List.of() : List.copyOf(resourceArgs);
        this.egressProxy = egressProxy;
        this.isolationProfile = egressProxy == null
                ? IsolationProfile.containerOpenNetwork()
                : IsolationProfile.containerProxiedNetwork();
    }

    @Override
    public Path workspace() {
        return workspace;
    }

    @Override
    public VerifyOutcome runVerify(RunBudget budget) {
        if (verifyScript == null) {
            return new VerifyOutcome(VerifyStatus.ERROR, -1, "no verify.sh present in case");
        }
        Path judgeDir = null;
        try {
            judgeDir = Files.createTempDirectory("mada-eval-docker-judge-");
            copyTree(workspace, judgeDir);
            // Mount the case directory (verify.sh + test.patch + harness siblings) at /judge.
            Path judgeBundle = verifyScript.toAbsolutePath().normalize().getParent();
            List<String> command = DockerRunCommand.shell(
                    dockerCommand,
                    image,
                    resourceArgs,
                    List.of("--network", "none"),
                    List.of(),
                    List.of(
                            "-v", judgeDir.toAbsolutePath() + ":/workspace:rw",
                            "-v", judgeBundle + ":/judge:ro"),
                    "/workspace",
                    "if command -v bash >/dev/null 2>&1; then "
                            + "bash /judge/verify.sh; else sh /judge/verify.sh; fi");

            ProcessSupervisor.Outcome outcome = new ProcessSupervisor().run(
                    command,
                    Path.of("").toAbsolutePath(),
                    budget.verifyTimeout().plus(Duration.ofSeconds(5)),
                    budget.maxProcessOutputBytes());
            VerifyStatus status = switch (outcome.status()) {
                case EXITED -> outcome.exitCode() == 0 ? VerifyStatus.PASSED : VerifyStatus.FAILED;
                case TIMED_OUT -> VerifyStatus.TIMED_OUT;
                case INTERRUPTED -> VerifyStatus.INTERRUPTED;
                case START_FAILED -> VerifyStatus.ERROR;
            };
            return new VerifyOutcome(status, outcome.exitCode(), outcome.output());
        } catch (IOException e) {
            return new VerifyOutcome(
                    VerifyStatus.ERROR,
                    -1,
                    "docker verify failed to run: " + e.getMessage());
        } finally {
            deleteTree(judgeDir);
        }
    }

    @Override
    public IsolationProfile isolationProfile() {
        return isolationProfile;
    }

    @Override
    public EgressReport egressReport() {
        return egressProxy == null ? EgressReport.unavailable() : egressProxy.egressReport();
    }

    @Override
    public void close() {
        if (egressProxy != null) {
            egressProxy.close();
        }
        deleteTree(workspace);
    }

    static Path seededWorkspace(EvalCaseLoader.LoadedCase loaded) {
        try {
            Path dir = Files.createTempDirectory("mada-eval-docker-" + loaded.evalCase().id() + "-");
            Path source = loaded.workspaceDir();
            if (Files.isDirectory(source)) {
                copyTree(source, dir);
            }
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create docker workspace for "
                    + loaded.evalCase().id(), e);
        }
    }

    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort temp cleanup
                }
            });
        } catch (IOException ignored) {
            // best-effort temp cleanup
        }
    }

    static void copyTree(Path src, Path dst) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                Path target = dst.resolve(src.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target);
                }
            }
        }
    }
}
