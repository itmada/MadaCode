package madacode.longrunning;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunningTaskStoreLifecycleTest {

    @TempDir
    Path tempDir;

    private LongRunningTaskStore store;

    @BeforeEach
    void setUp() {
        store = new LongRunningTaskStore(tempDir);
    }

    // ---- markTaskCompleted ----

    @Test
    void markTaskCompletedUpdatesStatusAndStage() {
        createTaskReadyToComplete("task-comp-1");

        LongRunningTaskMetadata result = store.markTaskCompleted("task-comp-1");

        assertEquals("DONE", result.status());
        assertEquals("DONE", result.stage());
    }

    @Test
    void markTaskCompletedPersistsToDisk() {
        createTaskReadyToComplete("task-comp-2");
        store.markTaskCompleted("task-comp-2");

        LongRunningTaskMetadata reloaded = store.loadTask("task-comp-2");
        assertEquals("DONE", reloaded.status());
        assertEquals("DONE", reloaded.stage());
    }

    @Test
    void markTaskCompletedRejectsNonExecutingTask() {
        createTaskReadyToComplete("task-comp-3");
        store.markTaskCompleted("task-comp-3");

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> store.markTaskCompleted("task-comp-3"));
        assertTrue(ex.getMessage().contains("cannot be completed"));
    }

    @Test
    void markTaskCompletedRejectsEmptyFeatureList() {
        store.createTask(new CreateTaskRequest("task-empty-features", "Test", "RUNNING", null, "s1", null));

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> store.markTaskCompleted("task-empty-features"));

        assertTrue(ex.getMessage().contains("feature list is empty"));
    }

    @Test
    void markTaskCompletedRejectsIncompleteFeatures() {
        store.createTask(new CreateTaskRequest("task-incomplete", "Test", "RUNNING", null, "s1", null));
        store.writeInitialFeatureList("task-incomplete", List.of(feature("feature-a")));

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> store.markTaskCompleted("task-incomplete"));

        assertTrue(ex.getMessage().contains("incomplete features"));
        assertTrue(ex.getMessage().contains("feature-a"));
    }

    @Test
    void markTaskCompletedRejectsOpenKnownIssue() {
        store.createTask(new CreateTaskRequest("task-open-issue", "Test", "RUNNING", null, "s1", null));
        store.writeInitialFeatureList("task-open-issue", List.of(feature("feature-a")));
        store.markFeaturePassed("task-open-issue", "feature-a");
        store.recordIssue("task-open-issue", issue("issue-a", "open"));

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> store.markTaskCompleted("task-open-issue"));

        assertTrue(ex.getMessage().contains("active known issues"));
        assertTrue(ex.getMessage().contains("issue-a"));
    }

    @Test
    void markTaskCompletedRejectsBlockedKnownIssue() {
        store.createTask(new CreateTaskRequest("task-blocked-issue", "Test", "RUNNING", null, "s1", null));
        store.writeInitialFeatureList("task-blocked-issue", List.of(feature("feature-a")));
        store.markFeaturePassed("task-blocked-issue", "feature-a");
        store.recordIssue("task-blocked-issue", issue("issue-a", "blocked"));

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> store.markTaskCompleted("task-blocked-issue"));

        assertTrue(ex.getMessage().contains("active known issues"));
        assertTrue(ex.getMessage().contains("issue-a"));
    }

    // ---- cancelTask ----

    @Test
    void cancelTaskUpdatesStatusAndStage() {
        store.createTask(new CreateTaskRequest("task-cancel-1", "Test", "RUNNING", null, "s1", null));

        LongRunningTaskMetadata result = store.cancelTask("task-cancel-1");

        assertEquals("DONE", result.status());
        assertEquals("DONE", result.stage());
    }

    @Test
    void cancelTaskPersistsToDisk() {
        store.createTask(new CreateTaskRequest("task-cancel-2", "Test", "RUNNING", null, "s1", null));
        store.cancelTask("task-cancel-2");

        LongRunningTaskMetadata reloaded = store.loadTask("task-cancel-2");
        assertEquals("DONE", reloaded.status());
        assertEquals("DONE", reloaded.stage());
    }

    @Test
    void cancelTaskAllowsPlanningTask() {
        store.createTask(new CreateTaskRequest("task-cancel-planning", "Test", "DRAFT", null, "s1", null));

        LongRunningTaskMetadata result = store.cancelTask("task-cancel-planning");

        assertEquals("DONE", result.status());
        assertEquals("DONE", result.stage());
    }

    @Test
    void cancelTaskAllowsPlanAwaitingApproval() {
        store.createTask(new CreateTaskRequest(
                "task-cancel-approval", "Test", "DRAFT", "s1", "DRAFT", null));

        LongRunningTaskMetadata result = store.cancelTask("task-cancel-approval");

        assertEquals("DONE", result.status());
        assertEquals("DONE", result.stage());
    }

    @Test
    void cancelTaskAllowsInitializedTask() {
        store.createTask(new CreateTaskRequest(
                "task-cancel-initialized", "Test", "DRAFT", "s1", "DRAFT", null));

        LongRunningTaskMetadata result = store.cancelTask("task-cancel-initialized");

        assertEquals("DONE", result.status());
        assertEquals("DONE", result.stage());
    }

    @Test
    void cancelTaskRejectsAlreadyCancelledTask() {
        store.createTask(new CreateTaskRequest("task-cancel-3", "Test", "RUNNING", null, "s1", null));
        store.cancelTask("task-cancel-3");

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> store.cancelTask("task-cancel-3"));
        assertTrue(ex.getMessage().contains("cannot be cancelled"));
    }

    @Test
    void completedTaskCannotBeCancelled() {
        createTaskReadyToComplete("task-comp-cancel");
        store.markTaskCompleted("task-comp-cancel");

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> store.cancelTask("task-comp-cancel"));
        assertTrue(ex.getMessage().contains("cannot be cancelled"));
    }

    private void createTaskReadyToComplete(String taskId) {
        store.createTask(new CreateTaskRequest(taskId, "Test", "RUNNING", null, "s1", null));
        store.writeInitialFeatureList(taskId, List.of(feature("feature-a")));
        store.markFeaturePassed(taskId, "feature-a");
    }

    private static FeatureItem feature(String id) {
        return new FeatureItem(id, "backend", "high", "Complete " + id, List.of(), List.of("Run tests"), false);
    }

    private static KnownIssue issue(String id, String status) {
        return new KnownIssue(
                id,
                "Unresolved behavior",
                "high",
                status,
                "RUNNING",
                List.of("Verify fix"),
                Instant.now(),
                null);
    }
}
