package madacode.longrunning;

import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunningTaskInitializerTest {

    @TempDir
    Path tempDir;

    private LongRunningTaskStore store() {
        return new LongRunningTaskStore(tempDir);
    }

    private LongRunningTaskInitializer initializer() {
        return new LongRunningTaskInitializer(store(),
                LongRunningTaskInitializer.TaskIdGenerator::defaultNewTaskId);
    }

    private ConversationSession session() {
        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DRAFT);
        return session;
    }

    @Test
    void createsTaskWhenSessionHasNoTaskId() throws Exception {
        ConversationSession session = session();
        session.setLongRunningStage(LongRunningStage.RUNNING);

        LongRunningTaskContext ctx = initializer().ensureExecutionTask(session, "implement the thing");

        assertNotNull(ctx.taskId());
        assertNotNull(ctx.taskDirectory());
        assertTrue(Files.isDirectory(ctx.taskDirectory()));
        assertTrue(Files.isRegularFile(ctx.taskDirectory().resolve("task.json")));
        assertTrue(Files.isRegularFile(ctx.taskDirectory().resolve("feature_list.json")));
        assertTrue(Files.isRegularFile(ctx.taskDirectory().resolve("progress.txt")));
        assertEquals(ctx.taskId(), session.longRunningTaskId());
        assertEquals(ctx.taskDirectory().toString(), session.longRunningTaskDirectory());
        assertEquals(LongRunningStage.RUNNING, session.longRunningStage());
        assertEquals("RUNNING", store().loadTask(ctx.taskId()).status());
        assertEquals("RUNNING", store().loadTask(ctx.taskId()).stage());

        String progress = Files.readString(ctx.taskDirectory().resolve("progress.txt"));
        assertTrue(progress.contains("stage: DRAFT") || progress.contains("stage: RUNNING"));
        assertTrue(progress.contains("implement the thing"));

        List<LongRunningTaskEvent> events = store().readEvents(ctx.taskId());
        assertTrue(events.stream().anyMatch(event -> "task_created".equals(event.type())
                && ctx.taskDirectory().toString().equals(event.details().get("taskDirectory"))));
        assertTrue(events.stream().anyMatch(event -> "workspace_checkpoint_created".equals(event.type())));
        assertTrue(store().readCheckpoint(ctx.taskId()).isPresent());
    }

    @Test
    void createsPlanningTaskBeforeExecutionApproval() throws Exception {
        ConversationSession session = session();
        session.setLongRunningStage(LongRunningStage.DRAFT);
        session.setLongRunningTaskTitle("Build a secondhand platform");

        LongRunningTaskContext ctx = initializer().ensurePlanningTask(session, "initial request");

        assertNotNull(ctx.taskId());
        assertEquals(ctx.taskId(), session.longRunningTaskId());
        assertEquals(ctx.taskDirectory().toString(), session.longRunningTaskDirectory());
        assertEquals(LongRunningStage.DRAFT, session.longRunningStage());
        assertEquals("DRAFT", store().loadTask(ctx.taskId()).status());
        assertEquals("DRAFT", store().loadTask(ctx.taskId()).stage());
        assertTrue(Files.isRegularFile(ctx.taskDirectory().resolve("logs/events.jsonl")));
        assertTrue(store().readEvents(ctx.taskId()).stream()
                .anyMatch(event -> "task_created".equals(event.type())
                        && "DRAFT".equals(event.details().get("status"))));
    }

    @Test
    void approvalInitializesPlanningTaskInsteadOfCreatingReplacement() throws Exception {
        ConversationSession session = session();
        session.setLongRunningStage(LongRunningStage.DRAFT);
        session.setLongRunningTaskTitle("Build marketplace");
        session.setLongRunningPlanSummary("Use Spring Boot and React. Implement auth and products first.");

        LongRunningTaskContext planning = initializer().ensurePlanningTask(session, "plan request");
        String taskId = planning.taskId();

        session.setLongRunningStage(LongRunningStage.RUNNING);
        LongRunningTaskContext initialized = initializer().ensureExecutionTask(session, "approved");

        assertEquals(taskId, initialized.taskId());
        assertEquals(taskId, session.longRunningTaskId());
        assertEquals(LongRunningStage.RUNNING, session.longRunningStage());
        assertEquals("RUNNING", store().loadTask(taskId).status());
        assertEquals("RUNNING", store().loadTask(taskId).stage());
        String taskJson = Files.readString(initialized.taskDirectory().resolve("task.json"));
        assertTrue(taskJson.contains("Build marketplace"));
        assertTrue(taskJson.contains("Spring Boot and React"));
        assertTrue(store().readEvents(taskId).stream()
                .anyMatch(event -> "task_execution_initialized".equals(event.type())));
        assertFalse(store().readEvents(taskId).stream()
                .anyMatch(event -> "task_execution_started".equals(event.type())));
    }

    @Test
    void runningTransitionInitializesPlanningTaskInsteadOfCrashing() throws Exception {
        ConversationSession session = session();
        session.setLongRunningStage(LongRunningStage.DRAFT);

        LongRunningTaskContext planning = initializer().ensurePlanningTask(session, "plan request");
        String taskId = planning.taskId();

        session.setLongRunningStage(LongRunningStage.RUNNING);
        LongRunningTaskContext initialized = initializer().ensureExecutionTask(session, "confirmed");

        assertEquals(taskId, initialized.taskId());
        assertEquals(taskId, session.longRunningTaskId());
        assertEquals(LongRunningStage.RUNNING, session.longRunningStage());
        assertEquals("RUNNING", store().loadTask(taskId).status());
        assertEquals("RUNNING", store().loadTask(taskId).stage());
        String progress = Files.readString(initialized.taskDirectory().resolve("progress.txt"));
        assertTrue(progress
                .contains("transition input: confirmed"));
        assertTrue(progress.contains("awaiting launcher/worker"));
        assertTrue(store().readEvents(taskId).stream()
                .anyMatch(event -> "task_execution_initialized".equals(event.type())));
    }

    @Test
    void repairsMissingTaskDirectoryForExistingTaskId() throws Exception {
        // Create a real task first
        LongRunningTaskStore store = store();
        LongRunningTaskMetadata meta = store.createTask(new CreateTaskRequest(
                "task-repair-test", "Repair test", "DRAFT", null, "session-r", null));
        Path realDir = store.taskDirectoryPath("task-repair-test");

        // Set up session with taskId but no taskDirectory
        ConversationSession session = session();
        session.setLongRunningTaskId("task-repair-test");
        // session.longRunningTaskDirectory is null by default
        session.setLongRunningStage(LongRunningStage.RUNNING);

        LongRunningTaskContext ctx = initializer().ensureExecutionTask(session, "repair");

        assertEquals("task-repair-test", ctx.taskId());
        assertEquals(realDir.toString(), session.longRunningTaskDirectory());
        assertEquals(realDir, ctx.taskDirectory());
        assertEquals(LongRunningStage.RUNNING, session.longRunningStage());

        List<LongRunningTaskEvent> events = store.readEvents("task-repair-test");
        assertTrue(events.stream().anyMatch(event -> "task_context_repaired".equals(event.type())));
    }

    @Test
    void repairsWrongTaskDirectoryForExistingTaskId() throws Exception {
        // Create a real task
        LongRunningTaskStore store = store();
        store.createTask(new CreateTaskRequest(
                "task-dir-fix", "Dir fix test", "DRAFT", null, "session-d", null));
        Path realDir = store.taskDirectoryPath("task-dir-fix");

        // Set up session with taskId and a wrong taskDirectory
        ConversationSession session = session();
        session.setLongRunningTaskId("task-dir-fix");
        session.setLongRunningTaskDirectory("/wrong/path");
        session.setLongRunningStage(LongRunningStage.RUNNING);

        LongRunningTaskContext ctx = initializer().ensureExecutionTask(session, "fix dir");

        assertEquals("task-dir-fix", ctx.taskId());
        assertEquals(realDir.toString(), session.longRunningTaskDirectory());
        assertTrue(Files.isDirectory(Path.of(session.longRunningTaskDirectory())));
    }

    @Test
    void throwsWhenExistingTaskDirectoryIsMissing() {
        ConversationSession session = session();
        session.setLongRunningTaskId("task-nonexistent");
        session.setLongRunningStage(LongRunningStage.RUNNING);

        assertThrows(LongRunningTaskStoreException.class,
                () -> initializer().ensureExecutionTask(session, "should fail"));
    }

    @Test
    void throwsWhenExistingTaskIsNotExecutable() {
        LongRunningTaskStore store = store();
        store.createTask(new CreateTaskRequest(
                "task-cancelled", "Cancelled task", "RUNNING", null, "session-x", null));
        store.cancelTask("task-cancelled");

        ConversationSession session = session();
        session.setLongRunningTaskId("task-cancelled");
        session.setLongRunningStage(LongRunningStage.RUNNING);

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> initializer().ensureExecutionTask(session, "should not resume"));
        assertTrue(ex.getMessage().contains("not ready"));
        assertTrue(ex.getMessage().contains("DONE"));
        assertEquals(LongRunningStage.RUNNING, session.longRunningStage());
    }

    @Test
    void retriesTaskIdCollisions() throws Exception {
        // Pre-create a task with the id that would be generated first
        LongRunningTaskStore store = store();
        store.createTask(new CreateTaskRequest(
                "task-collide", "Collision", "DRAFT", null, "session-c", null));

        ConversationSession session = session();
        session.setLongRunningStage(LongRunningStage.RUNNING);

        // Generator returns "task-collide" on attempt 0, "task-collide-1" on attempt 1
        LongRunningTaskInitializer.TaskIdGenerator collidingGen = attempt -> {
            if (attempt == 0) return "task-collide";
            return "task-collide-1";
        };
        LongRunningTaskInitializer init = new LongRunningTaskInitializer(store, collidingGen);

        LongRunningTaskContext ctx = init.ensureExecutionTask(session, "collision test");

        assertEquals("task-collide-1", ctx.taskId());
        assertEquals("task-collide-1", session.longRunningTaskId());
        assertTrue(Files.isDirectory(ctx.taskDirectory()));
        assertTrue(Files.isRegularFile(ctx.taskDirectory().resolve("task.json")));
    }
}
