package madacode.longrunning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunningMonitorReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsWorkerCycleAndReportFromEvents() {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest(
                "task-monitor", "Monitor", "RUNNING", null, "session-ctrl", null));
        store.appendEvent("task-monitor", LongRunningTaskEvent.of(
                "launcher_started",
                "task-monitor",
                "session-ctrl",
                "RUNNING",
                null,
                true,
                "Launcher started",
                Map.of("maxWorkers", "10")));
        store.appendEvent("task-monitor", LongRunningTaskEvent.of(
                "worker_started",
                "task-monitor",
                "session-ctrl",
                "RUNNING",
                null,
                true,
                "Starting worker cycle 3 of 12",
                Map.of("cycle", "3", "allowedCycles", "12")));
        store.appendEvent("task-monitor", LongRunningTaskEvent.of(
                "worker_report",
                "task-monitor",
                "worker-003",
                "RUNNING",
                "PROGRESS_MADE",
                true,
                "完成注册接口与测试修复",
                Map.of(
                        "workerSessionId", "worker-003",
                        "featureId", "F03",
                        "issueId", "",
                        "filesChanged", "backend/UserService.java")));

        LongRunningMonitorSnapshot snapshot = new LongRunningMonitorReader().read(
                tempDir, "task-monitor", false);

        assertEquals("task-monitor", snapshot.taskId());
        assertEquals("RUNNING", snapshot.stage());
        assertEquals("worker-003", snapshot.workerSessionId());
        assertEquals(3, snapshot.cycle());
        assertEquals(12, snapshot.limit());
        assertEquals("F03", snapshot.currentTarget());
        assertEquals("Report progress_made: 完成注册接口与测试修复", snapshot.currentAction());
        assertTrue(snapshot.recentEvents().stream()
                .anyMatch(line -> line.contains("Worker cycle 3 started")));
        assertTrue(snapshot.recentEvents().stream()
                .anyMatch(line -> line.contains("Report progress_made: 完成注册接口与测试修复")));
    }

    @Test
    void workerStartedClearsPreviousWorkerAndTarget() {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest(
                "task-next-worker", "Monitor", "RUNNING", null, "session-ctrl", null));
        store.appendEvent("task-next-worker", LongRunningTaskEvent.of(
                "worker_report",
                "task-next-worker",
                "worker-001",
                "RUNNING",
                "PROGRESS_MADE",
                true,
                "上一轮完成",
                Map.of(
                        "workerSessionId", "worker-001",
                        "featureId", "F01",
                        "issueId", "")));
        store.appendEvent("task-next-worker", LongRunningTaskEvent.of(
                "worker_started",
                "task-next-worker",
                "session-ctrl",
                "RUNNING",
                null,
                true,
                "Starting worker cycle 2 of 3",
                Map.of("cycle", "2", "allowedCycles", "3")));

        LongRunningMonitorSnapshot snapshot = new LongRunningMonitorReader().read(
                tempDir, "task-next-worker", false);

        assertEquals(null, snapshot.workerSessionId());
        assertEquals(null, snapshot.currentTarget());
        assertEquals(2, snapshot.cycle());
        assertEquals("Worker cycle running...", snapshot.currentAction());
    }

    @Test
    void unavailableTaskDoesNotCrashMonitor() {
        LongRunningMonitorSnapshot snapshot = new LongRunningMonitorReader().read(
                tempDir, "task-missing", true);

        assertEquals("task-missing", snapshot.taskId());
        assertTrue(snapshot.interrupting());
        assertTrue(snapshot.currentAction().startsWith("Monitor unavailable:"));
        assertEquals(1, snapshot.recentEvents().size());
    }
}
