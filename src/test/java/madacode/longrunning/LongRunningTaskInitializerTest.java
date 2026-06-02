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
        session.setLongRunningStage(LongRunningStage.WAITING_FOR_APPROVAL);
        return session;
    }

    @Test
    void createsTaskWhenSessionHasNoTaskId() throws Exception {
        ConversationSession session = session();
        session.setLongRunningStage(LongRunningStage.EXECUTING);

        LongRunningTaskContext ctx = initializer().ensureExecutionTask(session, "implement the thing");

        assertNotNull(ctx.taskId());
        assertNotNull(ctx.taskDirectory());
        assertTrue(Files.isDirectory(ctx.taskDirectory()));
        assertTrue(Files.isRegularFile(ctx.taskDirectory().resolve("task.json")));
        assertTrue(Files.isRegularFile(ctx.taskDirectory().resolve("feature_list.json")));
        assertTrue(Files.isRegularFile(ctx.taskDirectory().resolve("progress.txt")));
        assertEquals(ctx.taskId(), session.longRunningTaskId());
        assertEquals(ctx.taskDirectory().toString(), session.longRunningTaskDirectory());
        assertEquals(LongRunningStage.EXECUTING, session.longRunningStage());

        String progress = Files.readString(ctx.taskDirectory().resolve("progress.txt"));
        assertTrue(progress.contains("INITIALIZING -> EXECUTING"));
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
        session.setLongRunningStage(LongRunningStage.PLANNING);
        session.setLongRunningTaskTitle("Build a secondhand platform");

        LongRunningTaskContext ctx = initializer().ensurePlanningTask(session, "initial request");

        assertNotNull(ctx.taskId());
        assertEquals(ctx.taskId(), session.longRunningTaskId());
        assertEquals(ctx.taskDirectory().toString(), session.longRunningTaskDirectory());
        assertEquals(LongRunningStage.PLANNING, session.longRunningStage());
        assertEquals("planning", store().loadTask(ctx.taskId()).status());
        assertEquals("PLANNING", store().loadTask(ctx.taskId()).stage());
        assertTrue(Files.isRegularFile(ctx.taskDirectory().resolve("logs/events.jsonl")));
        assertTrue(store().readEvents(ctx.taskId()).stream()
                .anyMatch(event -> "task_created".equals(event.type())
                        && "planning".equals(event.details().get("status"))));
    }

    @Test
    void approvalPromotesPlanningTaskInsteadOfCreatingReplacement() throws Exception {
        ConversationSession session = session();
        session.setLongRunningStage(LongRunningStage.PLANNING);

        LongRunningTaskContext planning = initializer().ensurePlanningTask(session, "plan request");
        String taskId = planning.taskId();

        session.setLongRunningStage(LongRunningStage.INITIALIZING);
        LongRunningTaskContext executing = initializer().ensureExecutionTask(session, "approved");

        assertEquals(taskId, executing.taskId());
        assertEquals(taskId, session.longRunningTaskId());
        assertEquals(LongRunningStage.EXECUTING, session.longRunningStage());
        assertEquals("executing", store().loadTask(taskId).status());
        assertEquals("EXECUTING", store().loadTask(taskId).stage());
        assertTrue(store().readEvents(taskId).stream()
                .anyMatch(event -> "task_execution_started".equals(event.type())));
    }

    @Test
    void repairsMissingTaskDirectoryForExistingTaskId() throws Exception {
        // Create a real task first
        LongRunningTaskStore store = store();
        LongRunningTaskMetadata meta = store.createTask(new CreateTaskRequest(
                "task-repair-test", "Repair test", "executing", "session-r", "EXECUTING"));
        Path realDir = store.taskDirectoryPath("task-repair-test");

        // Set up session with taskId but no taskDirectory
        ConversationSession session = session();
        session.setLongRunningTaskId("task-repair-test");
        // session.longRunningTaskDirectory is null by default
        session.setLongRunningStage(LongRunningStage.EXECUTING);

        LongRunningTaskContext ctx = initializer().ensureExecutionTask(session, "repair");

        assertEquals("task-repair-test", ctx.taskId());
        assertEquals(realDir.toString(), session.longRunningTaskDirectory());
        assertEquals(realDir, ctx.taskDirectory());
        assertEquals(LongRunningStage.EXECUTING, session.longRunningStage());

        List<LongRunningTaskEvent> events = store.readEvents("task-repair-test");
        assertEquals(1, events.size());
        assertEquals("task_context_repaired", events.getFirst().type());
    }

    @Test
    void repairsWrongTaskDirectoryForExistingTaskId() throws Exception {
        // Create a real task
        LongRunningTaskStore store = store();
        store.createTask(new CreateTaskRequest(
                "task-dir-fix", "Dir fix test", "executing", "session-d", "EXECUTING"));
        Path realDir = store.taskDirectoryPath("task-dir-fix");

        // Set up session with taskId and a wrong taskDirectory
        ConversationSession session = session();
        session.setLongRunningTaskId("task-dir-fix");
        session.setLongRunningTaskDirectory("/wrong/path");
        session.setLongRunningStage(LongRunningStage.EXECUTING);

        LongRunningTaskContext ctx = initializer().ensureExecutionTask(session, "fix dir");

        assertEquals("task-dir-fix", ctx.taskId());
        assertEquals(realDir.toString(), session.longRunningTaskDirectory());
        assertTrue(Files.isDirectory(Path.of(session.longRunningTaskDirectory())));
    }

    @Test
    void throwsWhenExistingTaskDirectoryIsMissing() {
        ConversationSession session = session();
        session.setLongRunningTaskId("task-nonexistent");
        session.setLongRunningStage(LongRunningStage.EXECUTING);

        assertThrows(LongRunningTaskStoreException.class,
                () -> initializer().ensureExecutionTask(session, "should fail"));
    }

    @Test
    void throwsWhenExistingTaskIsNotExecutable() {
        LongRunningTaskStore store = store();
        store.createTask(new CreateTaskRequest(
                "task-cancelled", "Cancelled task", "executing", "session-x", "EXECUTING"));
        store.cancelTask("task-cancelled");

        ConversationSession session = session();
        session.setLongRunningTaskId("task-cancelled");
        session.setLongRunningStage(LongRunningStage.EXECUTING);

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> initializer().ensureExecutionTask(session, "should not resume"));
        assertTrue(ex.getMessage().contains("not executable"));
        assertTrue(ex.getMessage().contains("cancelled"));
        assertEquals(LongRunningStage.EXECUTING, session.longRunningStage());
    }

    @Test
    void retriesTaskIdCollisions() throws Exception {
        // Pre-create a task with the id that would be generated first
        LongRunningTaskStore store = store();
        store.createTask(new CreateTaskRequest(
                "task-collide", "Collision", "executing", "session-c", "EXECUTING"));

        ConversationSession session = session();
        session.setLongRunningStage(LongRunningStage.EXECUTING);

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
