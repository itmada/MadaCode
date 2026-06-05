package madacode.longrunning;

import madacode.core.session.ConversationSession;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public final class LongRunningRuntime implements AutoCloseable {

    private static final int DEFAULT_MAX_WORKER_CYCLES = 50;

    private final LongRunningLauncher launcher;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean interruptRequested = new AtomicBoolean();
    private final AtomicReference<Thread> launcherThread = new AtomicReference<>();
    private final AtomicReference<String> interruptReason = new AtomicReference<>();
    private volatile CompletableFuture<LongRunningLauncher.LaunchResult> current;
    private volatile CompletableFuture<LongRunningLauncher.LaunchResult> currentCompletion;
    private volatile String currentTaskId;
    private volatile Path currentProjectDir;

    public LongRunningRuntime(LongRunningLauncher launcher) {
        this(launcher, Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "mada-long-running-launcher");
            thread.setDaemon(true);
            return thread;
        }));
    }

    LongRunningRuntime(LongRunningLauncher launcher, ExecutorService executor) {
        this.launcher = Objects.requireNonNull(launcher, "launcher");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    public boolean start(
            String taskId,
            Path projectDir,
            ConversationSession controlSession,
            Consumer<Completion> onComplete) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(projectDir, "projectDir");
        Objects.requireNonNull(controlSession, "controlSession");
        Objects.requireNonNull(onComplete, "onComplete");
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        interruptRequested.set(false);
        interruptReason.set(null);
        CompletableFuture<LongRunningLauncher.LaunchResult> completion = new CompletableFuture<>();
        currentCompletion = completion;
        currentTaskId = taskId;
        currentProjectDir = projectDir;
        current = completion.whenComplete((result, error) -> {
            running.set(false);
            onComplete.accept(new Completion(taskId, result, error));
            currentCompletion = null;
            currentTaskId = null;
            currentProjectDir = null;
        });
        try {
            executor.execute(() -> {
                if (interruptRequested.get()) {
                    persistInterruptReason(projectDir, taskId);
                    completion.complete(new LongRunningLauncher.LaunchResult(
                            LongRunningLauncher.LaunchStatus.INTERRUPTED,
                            0,
                            "Launcher interrupted before starting."));
                    return;
                }
                launcherThread.set(Thread.currentThread());
                try {
                    LongRunningLauncher.LaunchResult result = launcher.run(
                            taskId, projectDir, controlSession, DEFAULT_MAX_WORKER_CYCLES);
                    if (result.status() == LongRunningLauncher.LaunchStatus.INTERRUPTED) {
                        persistInterruptReason(projectDir, taskId);
                    }
                    completion.complete(result);
                } catch (Throwable throwable) {
                    completion.completeExceptionally(throwable);
                } finally {
                    launcherThread.compareAndSet(Thread.currentThread(), null);
                }
            });
        } catch (RuntimeException exception) {
            current = null;
            currentCompletion = null;
            currentTaskId = null;
            currentProjectDir = null;
            running.set(false);
            throw exception;
        }
        return true;
    }

    public boolean interrupt(String reason) {
        if (!isRunning()) {
            return false;
        }
        interruptRequested.set(true);
        interruptReason.set(reason == null || reason.isBlank() ? "user_interrupted" : reason.strip());
        Thread thread = launcherThread.get();
        if (thread != null) {
            thread.interrupt();
        }
        return true;
    }

    public boolean isRunning() {
        CompletableFuture<LongRunningLauncher.LaunchResult> active = current;
        return running.get() && active != null && !active.isDone();
    }

    @Override
    public void close() {
        interrupt("runtime_closed");
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
        CompletableFuture<LongRunningLauncher.LaunchResult> pending = currentCompletion;
        if (pending != null && !pending.isDone()) {
            persistInterruptReason(currentProjectDir, currentTaskId);
            pending.complete(new LongRunningLauncher.LaunchResult(
                    LongRunningLauncher.LaunchStatus.INTERRUPTED,
                    0,
                    "Runtime closed before the launcher stopped."));
        }
    }

    private void persistInterruptReason(Path projectDir, String taskId) {
        String reason = interruptReason.get();
        if (projectDir == null || taskId == null || reason == null || reason.isBlank()) {
            return;
        }
        try {
            new LongRunningTaskStore(projectDir).markTaskInterrupted(taskId, reason);
        } catch (RuntimeException ignored) {
            // Completion reconciliation will surface the persisted task state.
        }
    }

    public record Completion(
            String taskId,
            LongRunningLauncher.LaunchResult result,
            Throwable error) {}
}
