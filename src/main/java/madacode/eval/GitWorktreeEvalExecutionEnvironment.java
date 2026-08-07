package madacode.eval;

import madacode.governance.IsolationProfile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Local SWE-style execution environment backed by detached Git worktrees.
 *
 * <p>The agent edits one worktree at the declared base commit. Verification creates a second,
 * clean worktree at that same commit and applies only the agent's Git diff. Package-manager
 * directories and other ignored build output therefore never cross the execution/judge
 * boundary.
 */
final class GitWorktreeEvalExecutionEnvironment implements EvalExecutionEnvironment {

    private static final Duration GIT_TIMEOUT = Duration.ofSeconds(30);
    private static final int GIT_OUTPUT_BYTES = 512 * 1024;
    private static final long MAX_CANDIDATE_PATCH_BYTES = 16L * 1024 * 1024;
    private static final ConcurrentMap<Path, ReentrantLock> REPOSITORY_LOCKS = new ConcurrentHashMap<>();
    private static final Set<GitWorktreeEvalExecutionEnvironment> ACTIVE =
            ConcurrentHashMap.newKeySet();
    private static final ReentrantLock VERIFY_LOCK = new ReentrantLock();
    private static final Path VERIFY_LOCK_FILE = Path.of(
            System.getProperty("java.io.tmpdir"), "mada-eval-verify.lock");

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(
                () -> ACTIVE.forEach(GitWorktreeEvalExecutionEnvironment::close),
                "mada-eval-worktree-cleanup"));
    }

    private final Path repository;
    private final String baseCommit;
    private final Path scratchDir;
    private final Path workspace;
    private final Path verifyScript;
    private final ProcessSupervisor supervisor = new ProcessSupervisor();
    private volatile boolean closed;

    private GitWorktreeEvalExecutionEnvironment(
            Path repository,
            String baseCommit,
            Path scratchDir,
            Path workspace,
            Path verifyScript) {
        this.repository = repository;
        this.baseCommit = baseCommit;
        this.scratchDir = scratchDir;
        this.workspace = workspace;
        this.verifyScript = verifyScript;
    }

    static EvalExecutionEnvironmentFactory factory(Path projectDir) {
        Path configuredCache = environmentPath("MADA_EVAL_REPO_CACHE");
        Path repositoryCache = configuredCache == null
                ? projectDir.toAbsolutePath().normalize().resolve("eval/_cache/repos")
                : configuredCache;
        return loaded -> loaded.evalCase().hasGitBaseline()
                ? create(loaded, repositoryCache)
                : Sandbox.of(loaded);
    }

    private static GitWorktreeEvalExecutionEnvironment create(
            EvalCaseLoader.LoadedCase loaded,
            Path repositoryCache) {
        EvalCase evalCase = loaded.evalCase();
        Path repository = repositoryCache
                .resolve(evalCase.repository().replace("/", "__"))
                .toAbsolutePath()
                .normalize();
        if (!Files.isDirectory(repository.resolve(".git"))) {
            throw new IllegalStateException(
                    "case " + evalCase.id() + ": cached repository is unavailable at " + repository
                            + "; materialize the SWE cases first or set MADA_EVAL_REPO_CACHE");
        }

        Path scratch;
        try {
            scratch = Files.createTempDirectory("mada-eval-git-" + evalCase.id() + "-");
        } catch (IOException e) {
            throw new UncheckedIOException("failed to create worktree parent for " + evalCase.id(), e);
        }
        Path workspace = scratch.resolve("agent");
        try {
            withRepositoryLock(repository, () -> {
                requireSuccess(runGit(repository, List.of(
                        "cat-file", "-e", evalCase.baseCommit() + "^{commit}")),
                        "base commit " + evalCase.baseCommit() + " is not present in " + repository);
                requireSuccess(runGit(repository, List.of(
                        "worktree", "add", "--detach", workspace.toString(), evalCase.baseCommit())),
                        "failed to create agent worktree");
            });
            Path verify = loaded.verifyScript();
            GitWorktreeEvalExecutionEnvironment environment = new GitWorktreeEvalExecutionEnvironment(
                    repository,
                    evalCase.baseCommit(),
                    scratch,
                    workspace,
                    Files.isRegularFile(verify) ? verify : null);
            ACTIVE.add(environment);
            return environment;
        } catch (RuntimeException e) {
            cleanupWorktree(repository, workspace);
            deleteTree(scratch);
            throw e;
        }
    }

    @Override
    public Path workspace() {
        return workspace;
    }

    @Override
    public IsolationProfile isolationProfile() {
        return IsolationProfile.localUnsafe();
    }

    @Override
    public VerifyOutcome runVerify(RunBudget budget) {
        if (verifyScript == null) {
            return new VerifyOutcome(VerifyStatus.ERROR, -1, "no verify.sh present in case");
        }
        Path candidatePatch = scratchDir.resolve("candidate.patch");
        Path judgeWorkspace = scratchDir.resolve("judge");
        VerifyLease verifyLease = null;
        try {
            verifyLease = acquireVerifyLease(budget.verifyTimeout());
            if (verifyLease == null) {
                return new VerifyOutcome(
                        VerifyStatus.TIMED_OUT,
                        -1,
                        "MADA_EVAL_VERIFY_LOCK_TIMEOUT: another eval is using the shared host verifier");
            }
            String patchFailure = writeCandidatePatch(candidatePatch);
            if (patchFailure != null) {
                return new VerifyOutcome(VerifyStatus.ERROR, -1, patchFailure);
            }
            withRepositoryLock(repository, () -> requireSuccess(runGit(repository, List.of(
                    "worktree", "add", "--detach", judgeWorkspace.toString(), baseCommit)),
                    "failed to create clean judge worktree"));
            if (Files.size(candidatePatch) > 0) {
                ProcessSupervisor.Outcome apply = runGit(judgeWorkspace, List.of(
                        "apply", "--whitespace=nowarn", candidatePatch.toString()));
                if (!apply.succeeded()) {
                    return new VerifyOutcome(
                            VerifyStatus.ERROR,
                            apply.exitCode(),
                            "MADA_EVAL_CANDIDATE_PATCH_ERROR: candidate diff could not be applied to "
                                    + baseCommit + "\n" + apply.output());
                }
            }
            ProcessSupervisor.Outcome outcome = supervisor.run(
                    List.of("bash", verifyScript.toString()),
                    judgeWorkspace,
                    budget.verifyTimeout(),
                    budget.maxProcessOutputBytes());
            VerifyStatus status = verifyStatus(outcome);
            return new VerifyOutcome(status, outcome.exitCode(), outcome.output());
        } catch (IOException e) {
            return new VerifyOutcome(
                    VerifyStatus.ERROR, -1, "clean judge worktree failed to run: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new VerifyOutcome(VerifyStatus.INTERRUPTED, -1, "verify interrupted");
        } catch (RuntimeException e) {
            return new VerifyOutcome(
                    VerifyStatus.ERROR, -1, "clean judge worktree failed to run: " + errorMessage(e));
        } finally {
            cleanupWorktree(repository, judgeWorkspace);
            try {
                Files.deleteIfExists(candidatePatch);
            } catch (IOException ignored) {
                // best-effort cleanup of attempt-owned temporary data
            }
            if (verifyLease != null) {
                verifyLease.close();
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        ACTIVE.remove(this);
        cleanupWorktree(repository, workspace);
        deleteTree(scratchDir);
    }

    private String writeCandidatePatch(Path destination) {
        ProcessSupervisor.Outcome intentToAdd = runGit(workspace, List.of(
                "add", "--intent-to-add", "--all"));
        if (!intentToAdd.succeeded()) {
            return "MADA_EVAL_CANDIDATE_PATCH_ERROR: failed to enumerate candidate files\n"
                    + intentToAdd.output();
        }
        // Hidden tests are injected by verify.sh; candidate edits to test files would make
        // that patch collide, so only product changes are transferred to the clean judge.
        ProcessSupervisor.Outcome diff = runGit(workspace, List.of(
                "diff", "--binary", "--full-index", "--no-ext-diff",
                "--output=" + destination, baseCommit, "--", ".",
                ":(exclude)test/**",
                ":(exclude)tests/**",
                ":(exclude)spec/**"));
        if (!diff.succeeded()) {
            return "MADA_EVAL_CANDIDATE_PATCH_ERROR: failed to create candidate diff\n" + diff.output();
        }
        try {
            long patchBytes = Files.size(destination);
            if (patchBytes > MAX_CANDIDATE_PATCH_BYTES) {
                return "MADA_EVAL_CANDIDATE_PATCH_ERROR: candidate diff is " + patchBytes
                        + " bytes, above the " + MAX_CANDIDATE_PATCH_BYTES + " byte limit";
            }
            return null;
        } catch (IOException e) {
            return "MADA_EVAL_CANDIDATE_PATCH_ERROR: failed to read candidate diff: " + e.getMessage();
        }
    }

    private static VerifyStatus verifyStatus(ProcessSupervisor.Outcome outcome) {
        VerifyStatus status = switch (outcome.status()) {
            case EXITED -> outcome.exitCode() == 0 ? VerifyStatus.PASSED : VerifyStatus.FAILED;
            case TIMED_OUT -> VerifyStatus.TIMED_OUT;
            case INTERRUPTED -> VerifyStatus.INTERRUPTED;
            case START_FAILED -> VerifyStatus.ERROR;
        };
        return status == VerifyStatus.FAILED && outcome.output().contains("MADA_EVAL_SETUP_ERROR")
                ? VerifyStatus.ERROR
                : status;
    }

    private static ProcessSupervisor.Outcome runGit(Path directory, List<String> arguments) {
        List<String> command = new java.util.ArrayList<>(arguments.size() + 3);
        command.add("git");
        command.add("-C");
        command.add(directory.toString());
        command.addAll(arguments);
        return new ProcessSupervisor().run(command, directory, GIT_TIMEOUT, GIT_OUTPUT_BYTES);
    }

    private static void requireSuccess(ProcessSupervisor.Outcome outcome, String action) {
        if (!outcome.succeeded()) {
            throw new IllegalStateException(action + "\n" + outcome.output());
        }
    }

    private static void cleanupWorktree(Path repository, Path worktree) {
        if (repository == null || worktree == null || !Files.exists(worktree)) {
            return;
        }
        withRepositoryLock(repository, () -> runGit(repository, List.of(
                "worktree", "remove", "--force", worktree.toString())));
    }

    private static void withRepositoryLock(Path repository, Runnable operation) {
        ReentrantLock lock = REPOSITORY_LOCKS.computeIfAbsent(
                repository.toAbsolutePath().normalize(), ignored -> new ReentrantLock());
        lock.lock();
        try {
            operation.run();
        } finally {
            lock.unlock();
        }
    }

    private static VerifyLease acquireVerifyLease(Duration timeout)
            throws IOException, InterruptedException {
        long timeoutMillis = Math.max(1, timeout.toMillis());
        if (!VERIFY_LOCK.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) {
            return null;
        }
        FileChannel channel = null;
        boolean handedOff = false;
        try {
            channel = FileChannel.open(
                    VERIFY_LOCK_FILE,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                try {
                    FileLock fileLock = channel.tryLock();
                    if (fileLock != null) {
                        handedOff = true;
                        return new VerifyLease(channel, fileLock);
                    }
                } catch (OverlappingFileLockException ignored) {
                    // Another thread in this JVM owns the OS lock.
                }
                Thread.sleep(100);
            }
            channel.close();
            return null;
        } catch (IOException | RuntimeException | Error e) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // best-effort lock cleanup
                }
            }
            throw e;
        } finally {
            if (!handedOff) {
                VERIFY_LOCK.unlock();
            }
        }
    }

    private static Path environmentPath(String variable) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    }

    private static String errorMessage(RuntimeException failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best-effort cleanup of attempt-owned temporary data
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup of attempt-owned temporary data
        }
    }

    private static final class VerifyLease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock fileLock;

        private VerifyLease(FileChannel channel, FileLock fileLock) {
            this.channel = channel;
            this.fileLock = fileLock;
        }

        @Override
        public void close() {
            try {
                fileLock.release();
            } catch (IOException ignored) {
                // best-effort lock cleanup
            } finally {
                try {
                    channel.close();
                } catch (IOException ignored) {
                    // best-effort lock cleanup
                }
                VERIFY_LOCK.unlock();
            }
        }
    }
}
