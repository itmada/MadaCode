package madacode.longrunning;

import madacode.core.model.FinishReason;
import madacode.core.session.ConversationSession;
import madacode.core.turn.TurnResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LongRunningRuntimeTest {

    @TempDir
    Path tempDir;

    @Test
    void executorSubmissionFailureDoesNotEmitCompletion() {
        LongRunningRuntime runtime = new LongRunningRuntime(
                new LongRunningLauncher(new FakeWorkerRunner()),
                new RejectingExecutorService());
        AtomicInteger completions = new AtomicInteger();

        RejectedExecutionException exception = assertThrows(
                RejectedExecutionException.class,
                () -> runtime.start(
                        "task-1",
                        tempDir,
                        new ConversationSession(tempDir),
                        completion -> completions.incrementAndGet()));

        assertEquals("executor rejected launcher", exception.getMessage());
        assertFalse(runtime.isRunning());
        assertEquals(0, completions.get());
    }

    private static final class FakeWorkerRunner extends LongRunningWorkerRunner {
        FakeWorkerRunner() {
            super((toolRegistry, promptBuilder) -> null,
                    new madacode.core.session.SessionStorage(Path.of(System.getProperty("java.io.tmpdir"))),
                    new madacode.tool.ToolRegistry(),
                    Path.of(System.getProperty("java.io.tmpdir")));
        }

        @Override
        public WorkerRunResult run(String taskId, Path projectDir) {
            return new WorkerRunResult(
                    "worker-session",
                    new TurnResult("ok", FinishReason.COMPLETED, 1),
                    Optional.of(new WorkerReport(
                            taskId,
                            "worker-session",
                            WorkerReport.Status.TASK_COMPLETED,
                            "done",
                            null,
                            null,
                            List.of(),
                            List.of(),
                            null)));
        }
    }

    private static final class RejectingExecutorService extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("executor rejected launcher");
        }
    }
}
