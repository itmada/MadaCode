package madacode.longrunning;

import madacode.core.session.ConversationSession;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class LongRunningRuntime implements AutoCloseable {

    private static final int DEFAULT_MAX_WORKER_CYCLES = 50;

    private final LongRunningLauncher launcher;
    private final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean interruptRequested = new AtomicBoolean();
    private final AtomicReference<Thread> launcherThread = new AtomicReference<>();
    private volatile CompletableFuture<LongRunningLauncher.LaunchResult> current;

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
        CompletableFuture<LongRunningLauncher.LaunchResult> completion = new CompletableFuture<>();
        current = completion.whenComplete((result, error) -> {
            running.set(false);
            onComplete.accept(new Completion(taskId, result, error));
        });
        try {
            executor.execute(() -> {
                if (interruptRequested.get()) {
                    completion.complete(new LongRunningLauncher.LaunchResult(
                            LongRunningLauncher.LaunchStatus.INTERRUPTED,
                            0,
                            "Launcher interrupted before starting."));
                    return;
                }
                launcherThread.set(Thread.currentThread());
                try {
                    completion.complete(launcher.run(
                            taskId, projectDir, controlSession, DEFAULT_MAX_WORKER_CYCLES));
                } catch (Throwable throwable) {
                    completion.completeExceptionally(throwable);
                } finally {
                    launcherThread.compareAndSet(Thread.currentThread(), null);
                }
            });
        } catch (RuntimeException exception) {
            current = null;
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
        executor.shutdownNow();
    }

    public record Completion(
            String taskId,
            LongRunningLauncher.LaunchResult result,
            Throwable error) {}
}
