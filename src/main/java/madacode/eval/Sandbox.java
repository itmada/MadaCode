package madacode.eval;

import madacode.governance.IsolationProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Local temporary-workspace backend for one eval case.
 *
 * <p>This isolates ordinary relative file operations but is deliberately classified as
 * {@link madacode.governance.IsolationProfile.IsolationLevel#LOCAL_UNSAFE}: absolute paths,
 * host processes, and network access are not contained. The judge still runs against a
 * separate snapshot of this workspace.
 */
public final class Sandbox implements EvalExecutionEnvironment {

    private final Path dir;
    private final Path verifyScript;

    private Sandbox(Path dir, Path verifyScript) {
        this.dir = dir;
        this.verifyScript = verifyScript;
    }

    /** Creates a local temporary workspace seeded from the case's {@code workspace/}. */
    public static Sandbox of(EvalCaseLoader.LoadedCase loaded) {
        try {
            Path dir = Files.createTempDirectory("mada-eval-" + loaded.evalCase().id() + "-");
            Path workspace = loaded.workspaceDir();
            if (Files.isDirectory(workspace)) {
                copyTree(workspace, dir);
            }
            // verify.sh stays outside the agent workspace and runs with cwd=judge snapshot.
            // Under LOCAL_UNSAFE it is still host-readable; a trusted hidden judge needs a
            // container/VM backend that only mounts the judge bundle during scoring.
            Path verify = loaded.verifyScript();
            return new Sandbox(dir, Files.isRegularFile(verify) ? verify : null);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create sandbox for " + loaded.evalCase().id(), e);
        }
    }

    @Override
    public Path workspace() {
        return dir;
    }

    @Override
    public IsolationProfile isolationProfile() {
        return IsolationProfile.localUnsafe();
    }

    /** Runs {@code verify.sh} in an immutable snapshot without mutating the agent workspace. */
    @Override
    public EvalExecutionEnvironment.VerifyOutcome runVerify(RunBudget budget) {
        if (verifyScript == null) {
            return new EvalExecutionEnvironment.VerifyOutcome(
                    VerifyStatus.ERROR, -1, "no verify.sh present in case");
        }
        Path judgeDir = null;
        try {
            judgeDir = Files.createTempDirectory("mada-eval-judge-");
            copyTree(dir, judgeDir);
            ProcessSupervisor.Outcome outcome = new ProcessSupervisor().run(
                    java.util.List.of("bash", verifyScript.toString()),
                    judgeDir,
                    budget.verifyTimeout(),
                    budget.maxProcessOutputBytes());
            VerifyStatus status = switch (outcome.status()) {
                case EXITED -> outcome.exitCode() == 0 ? VerifyStatus.PASSED : VerifyStatus.FAILED;
                case TIMED_OUT -> VerifyStatus.TIMED_OUT;
                case INTERRUPTED -> VerifyStatus.INTERRUPTED;
                case START_FAILED -> VerifyStatus.ERROR;
            };
            return new EvalExecutionEnvironment.VerifyOutcome(
                    status, outcome.exitCode(), outcome.output());
        } catch (IOException e) {
            return new EvalExecutionEnvironment.VerifyOutcome(
                    VerifyStatus.ERROR, -1, "verify.sh failed to run: " + e.getMessage());
        } finally {
            deleteTree(judgeDir);
        }
    }

    @Override
    public void close() {
        deleteTree(dir);
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // best-effort cleanup of a temp directory
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }

    private static void copyTree(Path src, Path dst) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = dst.resolve(src.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
    }

}
