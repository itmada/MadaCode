package madacode.longrunning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import madacode.core.model.FinishReason;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.core.turn.TurnResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

class LongRunningLauncherTest {

    @TempDir
    Path tempDir;

    @Test
    void progressMadeContinuesToNextWorker() {
        Path workingDirectory = tempDir.resolve("ws-progress");
        LongRunningTaskStore store = new LongRunningTaskStore(workingDirectory);
        store.createTask(new CreateTaskRequest(
                "task-1", "Test task", "initialized", "session-ctrl", "INITIALIZING"));
        // Set up features and mark them passed so markTaskCompleted succeeds
        store.writeInitialFeatureList("task-1", List.of(
                new FeatureItem("feat-1", "core", "high", "Feature 1", List.of(), List.of(), false)));
        store.markFeaturePassed("task-1", "feat-1");

        WorkerReport[] reports = {
                report(WorkerReport.Status.PROGRESS_MADE, "Did step 1"),
                report(WorkerReport.Status.TASK_COMPLETED, "All done")
        };
        int[] callCount = {0};

        LongRunningWorkerRunner fakeRunner = new FakeWorkerRunner(taskId -> {
            WorkerReport r = reports[Math.min(callCount[0], reports.length - 1)];
            callCount[0]++;
            return new LongRunningWorkerRunner.WorkerRunResult(
                    "worker-" + callCount[0],
                    new TurnResult("ok", FinishReason.COMPLETED, 1),
                    Optional.of(r));
        });

        LongRunningLauncher launcher = new LongRunningLauncher(fakeRunner);
        ConversationSession controlSession = controlSession(workingDirectory);

        LongRunningLauncher.LaunchResult result = launcher.run(
                "task-1", workingDirectory, controlSession, 5);

        assertEquals(LongRunningLauncher.LaunchStatus.COMPLETED, result.status());
        assertEquals(2, result.workersLaunched());
        assertTrue(result.message().contains("All done"));
    }

    @Test
    void taskCompletedStopsLauncher() {
        Path workingDirectory = tempDir.resolve("ws-complete");
        LongRunningTaskStore store = new LongRunningTaskStore(workingDirectory);
        store.createTask(new CreateTaskRequest(
                "task-1", "Test task", "initialized", "session-ctrl", "INITIALIZING"));
        // Set up features and mark them passed so markTaskCompleted succeeds
        store.writeInitialFeatureList("task-1", List.of(
                new FeatureItem("feat-1", "core", "high", "Feature 1", List.of(), List.of(), false)));
        store.markFeaturePassed("task-1", "feat-1");

        LongRunningWorkerRunner fakeRunner = new FakeWorkerRunner(taskId ->
                new LongRunningWorkerRunner.WorkerRunResult(
                        "worker-1",
                        new TurnResult("ok", FinishReason.COMPLETED, 1),
                        Optional.of(report(WorkerReport.Status.TASK_COMPLETED, "Done"))));

        LongRunningLauncher launcher = new LongRunningLauncher(fakeRunner);
        ConversationSession controlSession = controlSession(workingDirectory);

        LongRunningLauncher.LaunchResult result = launcher.run(
                "task-1", workingDirectory, controlSession, 5);

        assertEquals(LongRunningLauncher.LaunchStatus.COMPLETED, result.status());
        assertEquals(1, result.workersLaunched());
        LongRunningTaskMetadata meta = store.loadTask("task-1");
        assertEquals("completed", meta.status());
        assertEquals("COMPLETED", meta.stage());
        assertEquals(LongRunningStage.COMPLETED, controlSession.longRunningStage());
    }

    @Test
    void blockedStopsLauncher() {
        Path workingDirectory = tempDir.resolve("ws-blocked");
        LongRunningTaskStore store = new LongRunningTaskStore(workingDirectory);
        store.createTask(new CreateTaskRequest(
                "task-1", "Test task", "initialized", "session-ctrl", "INITIALIZING"));

        LongRunningWorkerRunner fakeRunner = new FakeWorkerRunner(taskId ->
                new LongRunningWorkerRunner.WorkerRunResult(
                        "worker-1",
                        new TurnResult("ok", FinishReason.COMPLETED, 1),
                        Optional.of(report(WorkerReport.Status.BLOCKED, "Missing dependency"))));

        LongRunningLauncher launcher = new LongRunningLauncher(fakeRunner);
        ConversationSession controlSession = controlSession(workingDirectory);

        LongRunningLauncher.LaunchResult result = launcher.run(
                "task-1", workingDirectory, controlSession, 5);

        assertEquals(LongRunningLauncher.LaunchStatus.BLOCKED, result.status());
        assertEquals(1, result.workersLaunched());
        assertTrue(result.message().contains("Missing dependency"));
    }

    @Test
    void failedStopsLauncher() {
        Path workingDirectory = tempDir.resolve("ws-failed");
        LongRunningTaskStore store = new LongRunningTaskStore(workingDirectory);
        store.createTask(new CreateTaskRequest(
                "task-1", "Test task", "initialized", "session-ctrl", "INITIALIZING"));

        LongRunningWorkerRunner fakeRunner = new FakeWorkerRunner(taskId ->
                new LongRunningWorkerRunner.WorkerRunResult(
                        "worker-1",
                        new TurnResult("ok", FinishReason.COMPLETED, 1),
                        Optional.of(report(WorkerReport.Status.FAILED, "Compilation error"))));

        LongRunningLauncher launcher = new LongRunningLauncher(fakeRunner);
        ConversationSession controlSession = controlSession(workingDirectory);

        LongRunningLauncher.LaunchResult result = launcher.run(
                "task-1", workingDirectory, controlSession, 5);

        assertEquals(LongRunningLauncher.LaunchStatus.FAILED, result.status());
        assertTrue(result.message().contains("Compilation error"));
    }

    @Test
    void needsUserStopsLauncher() {
        Path workingDirectory = tempDir.resolve("ws-needsuser");
        LongRunningTaskStore store = new LongRunningTaskStore(workingDirectory);
        store.createTask(new CreateTaskRequest(
                "task-1", "Test task", "initialized", "session-ctrl", "INITIALIZING"));

        LongRunningWorkerRunner fakeRunner = new FakeWorkerRunner(taskId ->
                new LongRunningWorkerRunner.WorkerRunResult(
                        "worker-1",
                        new TurnResult("ok", FinishReason.COMPLETED, 1),
                        Optional.of(report(WorkerReport.Status.NEEDS_USER, "Need clarification"))));

        LongRunningLauncher launcher = new LongRunningLauncher(fakeRunner);
        ConversationSession controlSession = controlSession(workingDirectory);

        LongRunningLauncher.LaunchResult result = launcher.run(
                "task-1", workingDirectory, controlSession, 5);

        assertEquals(LongRunningLauncher.LaunchStatus.NEEDS_USER, result.status());
        assertTrue(result.message().contains("Need clarification"));
    }

    @Test
    void noReportStopsLauncherAsFailed() {
        Path workingDirectory = tempDir.resolve("ws-noreport");
        LongRunningTaskStore store = new LongRunningTaskStore(workingDirectory);
        store.createTask(new CreateTaskRequest(
                "task-1", "Test task", "initialized", "session-ctrl", "INITIALIZING"));

        LongRunningWorkerRunner fakeRunner = new FakeWorkerRunner(taskId ->
                new LongRunningWorkerRunner.WorkerRunResult(
                        "worker-1",
                        new TurnResult("ok", FinishReason.COMPLETED, 1),
                        Optional.empty()));

        LongRunningLauncher launcher = new LongRunningLauncher(fakeRunner);
        ConversationSession controlSession = controlSession(workingDirectory);

        LongRunningLauncher.LaunchResult result = launcher.run(
                "task-1", workingDirectory, controlSession, 5);

        assertEquals(LongRunningLauncher.LaunchStatus.FAILED, result.status());
        assertEquals(1, result.workersLaunched());
        assertTrue(result.message().contains("did not produce a worker_report"));
    }

    @Test
    void maxWorkersExhaustedReturnsExhaustedStatus() {
        Path workingDirectory = tempDir.resolve("ws-exhausted");
        LongRunningTaskStore store = new LongRunningTaskStore(workingDirectory);
        store.createTask(new CreateTaskRequest(
                "task-1", "Test task", "initialized", "session-ctrl", "INITIALIZING"));

        LongRunningWorkerRunner fakeRunner = new FakeWorkerRunner(taskId ->
                new LongRunningWorkerRunner.WorkerRunResult(
                        "worker-1",
                        new TurnResult("ok", FinishReason.COMPLETED, 1),
                        Optional.of(report(WorkerReport.Status.PROGRESS_MADE, "Step done"))));

        LongRunningLauncher launcher = new LongRunningLauncher(fakeRunner);
        ConversationSession controlSession = controlSession(workingDirectory);

        LongRunningLauncher.LaunchResult result = launcher.run(
                "task-1", workingDirectory, controlSession, 3);

        assertEquals(LongRunningLauncher.LaunchStatus.MAX_WORKERS_EXHAUSTED, result.status());
        assertEquals(3, result.workersLaunched());
    }

    private ConversationSession controlSession(Path workingDirectory) {
        ConversationSession session = new ConversationSession(workingDirectory);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.INITIALIZING);
        session.setLongRunningTaskId("task-1");
        return session;
    }

    private WorkerReport report(WorkerReport.Status status, String summary) {
        return new WorkerReport(
                "task-1", "worker-session", status, summary,
                null, null, List.of(), List.of(), null);
    }

    @FunctionalInterface
    interface FakeRunnerFunction {
        LongRunningWorkerRunner.WorkerRunResult run(String taskId);
    }

    private static class FakeWorkerRunner extends LongRunningWorkerRunner {
        private final FakeRunnerFunction function;

        FakeWorkerRunner(FakeRunnerFunction function) {
            super((tr, pb) -> null,
                    new madacode.core.session.SessionStorage(Path.of(System.getProperty("java.io.tmpdir"))),
                    new madacode.tool.ToolRegistry(),
                    Path.of(System.getProperty("java.io.tmpdir")));
            this.function = function;
        }

        @Override
        public WorkerRunResult run(String taskId, Path projectDir) {
            return function.run(taskId);
        }
    }
}
