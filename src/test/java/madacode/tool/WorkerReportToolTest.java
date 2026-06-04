package madacode.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.core.model.ToolResult;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.longrunning.LongRunningTaskStore;
import madacode.longrunning.CreateTaskRequest;
import madacode.longrunning.WorkerReport;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class WorkerReportToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private WorkerReportTool tool;

    @BeforeEach
    void setUp() {
        tool = new WorkerReportTool();
    }

    @Test
    void failsOutsideLongRunningMode() {
        ConversationSession session = new ConversationSession(tempDir);
        ToolUseContext context = new ToolUseContext(tempDir, session);

        ToolResult result = ToolTestSupport.invoke(tool,
                input("task-1", "progress_made", "did stuff"), context);

        assertFalse(result.success());
        assertTrue(result.output().contains("Long-running mode is not active"));
    }

    @Test
    void failsInControlSession() {
        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);
        session.setLongRunningTaskId("task-1");
        // NOT a worker session
        ToolUseContext context = new ToolUseContext(tempDir, session);

        ToolResult result = ToolTestSupport.invoke(tool,
                input("task-1", "progress_made", "did stuff"), context);

        assertFalse(result.success());
        assertTrue(result.output().contains("only available in a long-running worker session"));
    }

    @Test
    void failsWhenTaskIdMismatch() {
        ConversationSession session = workerSession("task-actual");
        ToolUseContext context = new ToolUseContext(tempDir, session);

        ToolResult result = ToolTestSupport.invoke(tool,
                input("task-wrong", "progress_made", "did stuff"), context);

        assertFalse(result.success());
        assertTrue(result.output().contains("does not match"));
    }

    @Test
    void failsWithInvalidStatus() {
        ConversationSession session = workerSession("task-1");
        ToolUseContext context = new ToolUseContext(tempDir, session);

        ToolResult result = ToolTestSupport.invoke(tool,
                input("task-1", "invalid_status", "did stuff"), context);

        assertFalse(result.success());
        assertTrue(result.output().contains("Invalid status"));
    }

    @Test
    void failsWithBlankSummary() {
        ConversationSession session = workerSession("task-1");
        ToolUseContext context = new ToolUseContext(tempDir, session);

        ToolResult result = ToolTestSupport.invoke(tool,
                input("task-1", "progress_made", "  "), context);

        assertFalse(result.success());
        assertTrue(result.output().contains("summary must be non-empty"));
    }

    @Test
    void succeedsAndRecordsReport() throws Exception {
        Path workingDirectory = tempDir.resolve("ws-report");
        LongRunningTaskStore store = new LongRunningTaskStore(workingDirectory);
        store.createTask(new CreateTaskRequest(
                "task-report", "Test task", "RUNNING", null, "session-ctrl", null));

        ConversationSession session = workerSession("task-report", workingDirectory);
        ToolUseContext context = new ToolUseContext(workingDirectory, session);

        ObjectNode input = input("task-report", "progress_made", "Implemented feature X");
        ArrayNode filesChanged = mapper.createArrayNode();
        filesChanged.add("src/Main.java");
        filesChanged.add("src/Utils.java");
        input.set("files_changed", filesChanged);
        ArrayNode verification = mapper.createArrayNode();
        verification.add("mvn test passed");
        input.set("verification", verification);
        input.put("feature_id", "feat-1");
        input.put("next", "Continue with feat-2");

        ToolResult result = ToolTestSupport.invoke(tool, input, context);

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("progress_made"));

        // Verify session has the report
        assertTrue(session.lastWorkerReport().isPresent());
        WorkerReport report = session.lastWorkerReport().get();
        assertEquals("task-report", report.taskId());
        assertEquals(WorkerReport.Status.PROGRESS_MADE, report.status());
        assertEquals("Implemented feature X", report.summary());
        assertEquals("feat-1", report.featureId());
        assertEquals(List.of("src/Main.java", "src/Utils.java"), report.filesChanged());
        assertEquals(List.of("mvn test passed"), report.verification());
        assertEquals("Continue with feat-2", report.next());

        // Verify events.jsonl has worker_report event
        List<madacode.longrunning.LongRunningTaskEvent> events = store.readEvents("task-report");
        assertTrue(events.stream().anyMatch(e -> "worker_report".equals(e.type())
                && "PROGRESS_MADE".equals(e.action())));

        String progress = Files.readString(workingDirectory.resolve(".mada/long-running/task-report/progress.txt"));
        assertTrue(progress.contains("Implemented feature X"));
        assertTrue(progress.contains("progress_made"));
        assertTrue(progress.contains("files_changed: src/Main.java, src/Utils.java"));
    }

    @Test
    void taskCompletedStatusRecordsSuccessfully() throws Exception {
        Path workingDirectory = tempDir.resolve("ws-complete");
        LongRunningTaskStore store = new LongRunningTaskStore(workingDirectory);
        store.createTask(new CreateTaskRequest(
                "task-complete", "Test task", "RUNNING", null, "session-ctrl", null));

        ConversationSession session = workerSession("task-complete", workingDirectory);
        ToolUseContext context = new ToolUseContext(workingDirectory, session);

        ToolResult result = ToolTestSupport.invoke(tool,
                input("task-complete", "task_completed", "All features done"), context);

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("task_completed"));
        assertEquals(WorkerReport.Status.TASK_COMPLETED, session.lastWorkerReport().get().status());
    }

    @Test
    void failsWhenWorkerReportsTwice() {
        Path workingDirectory = tempDir.resolve("ws-duplicate");
        LongRunningTaskStore store = new LongRunningTaskStore(workingDirectory);
        store.createTask(new CreateTaskRequest(
                "task-duplicate", "Test task", "RUNNING", null, "session-ctrl", null));
        ConversationSession session = workerSession("task-duplicate", workingDirectory);
        ToolUseContext context = new ToolUseContext(workingDirectory, session);

        ToolResult first = ToolTestSupport.invoke(tool,
                input("task-duplicate", "progress_made", "first"), context);
        ToolResult second = ToolTestSupport.invoke(tool,
                input("task-duplicate", "progress_made", "second"), context);

        assertTrue(first.success(), first.output());
        assertFalse(second.success());
        assertTrue(second.output().contains("already been recorded"));
        assertEquals("first", session.lastWorkerReport().orElseThrow().summary());
    }

    private ConversationSession workerSession(String taskId) {
        return workerSession(taskId, tempDir);
    }

    private ConversationSession workerSession(String taskId, Path workingDirectory) {
        ConversationSession session = new ConversationSession(workingDirectory);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);
        session.setLongRunningWorkerSession(true);
        session.setLongRunningTaskId(taskId);
        return session;
    }

    private ObjectNode input(String taskId, String status, String summary) {
        ObjectNode input = mapper.createObjectNode();
        input.put("task_id", taskId);
        input.put("status", status);
        input.put("summary", summary);
        return input;
    }
}
