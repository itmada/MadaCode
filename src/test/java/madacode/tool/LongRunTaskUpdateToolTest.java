package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import madacode.core.engine.ToolUseContext;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.longrunning.CreateTaskRequest;
import madacode.longrunning.LongRunningTaskMetadata;
import madacode.longrunning.LongRunningTaskStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunTaskUpdateToolTest {

    private final LongRunTaskUpdateTool tool = new LongRunTaskUpdateTool();
    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void writesInitialFeaturesAndAppendsProgressThroughTaskStore() throws Exception {
        ConversationSession session = initializedSession("task-001");
        LongRunTaskUpdateTool.Input writeFeatures = new LongRunTaskUpdateTool.Input(
                "write_initial_feature_list",
                null,
                List.of(new LongRunTaskUpdateTool.FeatureInput(
                        "feature-a",
                        "backend",
                        "high",
                        "Create mode router",
                        List.of(),
                        List.of("Run mode tests"))),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        var featureResult = tool.execute(writeFeatures, context(session));
        var progressResult = tool.execute(new LongRunTaskUpdateTool.Input(
                "append_progress",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "seeded feature list"),
                context(session));

        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        assertTrue(featureResult.success());
        assertTrue(progressResult.success());
        assertEquals(1, store.readFeatureList("task-001").size());
        assertEquals("Create mode router", store.readFeatureList("task-001").getFirst().description());
        assertTrue(Files.readString(tempDir.resolve(".mada/long-running/task-001/progress.txt"))
                .contains("seeded feature list"));
    }

    @Test
    void activeKnownIssueBlocksFeaturePassUntilResolved() {
        ConversationSession session = initializedSession("task-002");
        LongRunTaskUpdateTool.Input features = new LongRunTaskUpdateTool.Input(
                "write_initial_feature_list",
                null,
                List.of(new LongRunTaskUpdateTool.FeatureInput(
                        "feature-a",
                        "backend",
                        "high",
                        "Fix state machine",
                        List.of(),
                        List.of("Run tests"))),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        tool.execute(features, context(session));
        tool.execute(new LongRunTaskUpdateTool.Input(
                "record_issue",
                null,
                null,
                null,
                "issue-a",
                "Unexpected task state",
                "high",
                "open",
                null,
                "RUNNING",
                List.of("Verify fix"),
                null),
                context(session));

        var blocked = tool.execute(new LongRunTaskUpdateTool.Input(
                "mark_feature_passed",
                null,
                null,
                "feature-a",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null),
                context(session));
        tool.execute(new LongRunTaskUpdateTool.Input(
                "resolve_issue",
                null,
                null,
                null,
                "issue-a",
                null,
                null,
                null,
                null,
                null,
                null,
                null),
                context(session));
        var passed = tool.execute(new LongRunTaskUpdateTool.Input(
                "mark_feature_passed",
                null,
                null,
                "feature-a",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null),
                context(session));

        assertFalse(blocked.success());
        assertTrue(blocked.output().contains("known issues"));
        assertTrue(passed.success());
        assertTrue(new LongRunningTaskStore(tempDir)
                .readFeatureList("task-002")
                .getFirst()
                .passes());
    }

    @Test
    void rejectsMismatchedTaskId() {
        ConversationSession session = initializedSession("task-003");

        var result = tool.execute(new LongRunTaskUpdateTool.Input(
                "append_progress",
                "other-task",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "progress"),
                context(session));

        assertFalse(result.success());
        assertTrue(result.output().contains("does not match"));
    }

    @Test
    void schemaExposesRequiredAction() {
        var schema = tool.inputSchema(mapper);

        assertTrue(schema.path("properties").has("action"));
        boolean required = false;
        for (var field : schema.path("required")) {
            if ("action".equals(field.asText())) {
                required = true;
            }
        }
        assertTrue(required);
    }

    @Test
    void markTaskCompleteUpdatesStoreAndSessionStage() {
        ConversationSession session = initializedSession("task-010");
        tool.execute(new LongRunTaskUpdateTool.Input(
                "write_initial_feature_list",
                null,
                List.of(new LongRunTaskUpdateTool.FeatureInput(
                        "feature-a",
                        "backend",
                        "high",
                        "Complete the implementation",
                        List.of(),
                        List.of("Run tests"))),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null),
                context(session));
        tool.execute(new LongRunTaskUpdateTool.Input(
                "mark_feature_passed",
                null,
                null,
                "feature-a",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null),
                context(session));

        var complete = tool.execute(new LongRunTaskUpdateTool.Input(
                "mark_task_complete",
                null, null, null, null, null, null, null, null, null, null, null),
                context(session));
        var cancel = tool.execute(new LongRunTaskUpdateTool.Input(
                "cancel_task",
                null, null, null, null, null, null, null, null, null, null, null),
                context(session));

        assertFalse(complete.success());
        assertFalse(cancel.success());
        assertTrue(complete.output().contains("Unsupported"));
        assertTrue(cancel.output().contains("Unsupported"));
        assertEquals(LongRunningStage.RUNNING, session.longRunningStage());
        assertEquals("RUNNING", new LongRunningTaskStore(tempDir).loadTask("task-010").status());
    }

    @Test
    void taskUpdateToolRejectsNonExecutingStage() {
        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DRAFT);

        var result = tool.execute(new LongRunTaskUpdateTool.Input(
                "append_progress",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "progress"),
                context(session));

        assertFalse(result.success());
        assertTrue(result.output().contains("RUNNING"));
    }

    @Test
    void updateIssueStatusTransitionsOpenToBlocked() {
        ConversationSession session = initializedSession("task-020");
        tool.execute(new LongRunTaskUpdateTool.Input(
                "write_initial_feature_list",
                null,
                List.of(new LongRunTaskUpdateTool.FeatureInput(
                        "f1", "cat", "high", "desc", List.of(), List.of("v"))),
                null, null, null, null, null, null, null, null, null),
                context(session));
        tool.execute(new LongRunTaskUpdateTool.Input(
                "record_issue",
                null, null, null, "issue-1", "desc", "high", "open", null, "RUNNING", List.of(), null),
                context(session));

        var result = tool.execute(new LongRunTaskUpdateTool.Input(
                "update_issue_status",
                null, null, null, "issue-1", null, null, null, "blocked", null, null, null),
                context(session));

        assertTrue(result.success());
        assertTrue(result.output().contains("blocked"));

        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        var issues = store.readKnownIssues("task-020");
        assertEquals("blocked", issues.getFirst().status());
    }

    @Test
    void updateIssueStatusRejectsResolvedToOpen() {
        ConversationSession session = initializedSession("task-021");
        tool.execute(new LongRunTaskUpdateTool.Input(
                "write_initial_feature_list",
                null,
                List.of(new LongRunTaskUpdateTool.FeatureInput(
                        "f1", "cat", "high", "desc", List.of(), List.of("v"))),
                null, null, null, null, null, null, null, null, null),
                context(session));
        tool.execute(new LongRunTaskUpdateTool.Input(
                "record_issue",
                null, null, null, "issue-1", "desc", "high", "open", null, "RUNNING", List.of(), null),
                context(session));
        tool.execute(new LongRunTaskUpdateTool.Input(
                "resolve_issue",
                null, null, null, "issue-1", null, null, null, null, null, null, null),
                context(session));

        var result = tool.execute(new LongRunTaskUpdateTool.Input(
                "update_issue_status",
                null, null, null, "issue-1", null, null, null, "open", null, null, null),
                context(session));

        assertFalse(result.success());
        assertTrue(result.output().contains("resolved"));
    }

    @Test
    void repairsMissingTaskDirectoryOnSessionWhenStoreExists() throws Exception {
        // Create task on disk but do NOT set taskDirectory on session
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest(
                "task-repair-dir", "Repair dir test", "RUNNING", null, "session-rd", null));
        Path realDir = store.taskDirectoryPath("task-repair-dir");

        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);
        session.setLongRunningTaskId("task-repair-dir");
        // taskDirectory intentionally left null

        var result = tool.execute(new LongRunTaskUpdateTool.Input(
                "append_progress",
                null, null, null, null, null, null, null, null, null, null,
                "repaired progress"),
                context(session));

        assertTrue(result.success());
        assertTrue(result.output().contains("Progress appended"));
        assertEquals(realDir.toString(), session.longRunningTaskDirectory());
        assertTrue(Files.readString(realDir.resolve("progress.txt")).contains("repaired progress"));
        var events = store.readEvents("task-repair-dir");
        assertEquals(1, events.size());
        assertEquals("task_update", events.getFirst().type());
        assertEquals("append_progress", events.getFirst().action());
        assertTrue(events.getFirst().success());
        assertTrue(events.getFirst().message().contains("Progress appended"));

        // Subsequent operations should also work
        var progressive = tool.execute(new LongRunTaskUpdateTool.Input(
                "append_progress",
                null, null, null, null, null, null, null, null, null, null,
                "second append"),
                context(session));
        assertTrue(progressive.success());
    }

    @Test
    void failsWhenTaskDirectoryDoesNotExistOnDisk() {
        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);
        session.setLongRunningTaskId("task-missing");

        var result = tool.execute(new LongRunTaskUpdateTool.Input(
                "append_progress",
                null, null, null, null, null, null, null, null, null, null,
                "should fail"),
                context(session));

        assertFalse(result.success());
        assertTrue(result.output().contains("Task directory not found"));
        assertNull(session.longRunningTaskDirectory());
    }

    @Test
    void rejectsWorkerMutationWhenTaskStoreReturnedToDraft() {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest(
                "task-paused", "Paused", "DRAFT", null, "session-ctrl", null));

        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);
        session.setLongRunningTaskId("task-paused");

        var result = tool.execute(new LongRunTaskUpdateTool.Input(
                "append_progress",
                null, null, null, null, null, null, null, null, null, null,
                "late write"),
                context(session));

        assertFalse(result.success());
        assertTrue(result.output().contains("is not running"));
    }

    private ConversationSession initializedSession(String taskId) {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest(
                taskId,
                "Test task",
                "RUNNING",
                null,
                "session-1",
                null));
        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);
        session.setLongRunningTaskId(taskId);
        session.setLongRunningTaskDirectory(tempDir
                .resolve(".mada/long-running")
                .resolve(taskId)
                .toString());
        return session;
    }

    private ToolUseContext context(ConversationSession session) {
        return new ToolUseContext(tempDir, session);
    }
}
