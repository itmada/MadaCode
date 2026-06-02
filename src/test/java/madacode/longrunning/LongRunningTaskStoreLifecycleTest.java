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

        assertEquals("completed", result.status());
        assertEquals("COMPLETED", result.stage());
    }

    @Test
    void markTaskCompletedPersistsToDisk() {
        createTaskReadyToComplete("task-comp-2");
        store.markTaskCompleted("task-comp-2");

        LongRunningTaskMetadata reloaded = store.loadTask("task-comp-2");
        assertEquals("completed", reloaded.status());
        assertEquals("COMPLETED", reloaded.stage());
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
        store.createTask(new CreateTaskRequest("task-empty-features", "Test", "executing", "s1", "EXECUTING"));

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> store.markTaskCompleted("task-empty-features"));

        assertTrue(ex.getMessage().contains("feature list is empty"));
    }

    @Test
    void markTaskCompletedRejectsIncompleteFeatures() {
        store.createTask(new CreateTaskRequest("task-incomplete", "Test", "executing", "s1", "EXECUTING"));
        store.writeInitialFeatureList("task-incomplete", List.of(feature("feature-a")));

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> store.markTaskCompleted("task-incomplete"));

        assertTrue(ex.getMessage().contains("incomplete features"));
        assertTrue(ex.getMessage().contains("feature-a"));
    }

    @Test
    void markTaskCompletedRejectsOpenKnownIssue() {
        store.createTask(new CreateTaskRequest("task-open-issue", "Test", "executing", "s1", "EXECUTING"));
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
        store.createTask(new CreateTaskRequest("task-blocked-issue", "Test", "executing", "s1", "EXECUTING"));
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
        store.createTask(new CreateTaskRequest("task-cancel-1", "Test", "executing", "s1", "EXECUTING"));

        LongRunningTaskMetadata result = store.cancelTask("task-cancel-1");

        assertEquals("cancelled", result.status());
        assertEquals("CANCELLED", result.stage());
    }

    @Test
    void cancelTaskPersistsToDisk() {
        store.createTask(new CreateTaskRequest("task-cancel-2", "Test", "executing", "s1", "EXECUTING"));
        store.cancelTask("task-cancel-2");

        LongRunningTaskMetadata reloaded = store.loadTask("task-cancel-2");
        assertEquals("cancelled", reloaded.status());
        assertEquals("CANCELLED", reloaded.stage());
    }

    @Test
    void cancelTaskRejectsNonExecutingTask() {
        store.createTask(new CreateTaskRequest("task-cancel-3", "Test", "executing", "s1", "EXECUTING"));
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
        store.createTask(new CreateTaskRequest(taskId, "Test", "executing", "s1", "EXECUTING"));
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
                "EXECUTING",
                List.of("Verify fix"),
                Instant.now(),
                null);
    }
}
