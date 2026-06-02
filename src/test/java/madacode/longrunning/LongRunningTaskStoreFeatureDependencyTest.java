package madacode.longrunning;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunningTaskStoreFeatureDependencyTest {

    @TempDir
    Path tempDir;

    private LongRunningTaskStore store;

    @BeforeEach
    void setUp() {
        store = new LongRunningTaskStore(tempDir);
    }

    private String createTask(String taskId) {
        store.createTask(new CreateTaskRequest(taskId, "Test task", "executing", "session-1", "EXECUTING"));
        return taskId;
    }

    // ---- Dependency existence validation ----

    @Test
    void rejectsDependencyOnNonExistentFeature() {
        String taskId = createTask("task-dep-1");
        List<FeatureItem> features = List.of(
                new FeatureItem("f1", "cat", "high", "desc", List.of("nonexistent"), List.of("v"), false));

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> store.writeInitialFeatureList(taskId, features));
        assertTrue(ex.getMessage().contains("unknown feature"));
    }

    // ---- Self-dependency validation ----

    @Test
    void rejectsSelfDependency() {
        String taskId = createTask("task-self-dep");
        List<FeatureItem> features = List.of(
                new FeatureItem("f1", "cat", "high", "desc", List.of("f1"), List.of("v"), false));

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> store.writeInitialFeatureList(taskId, features));
        assertTrue(ex.getMessage().contains("self-dependency"));
    }

    // ---- Circular dependency detection ----

    @Test
    void rejectsCircularDependencyAB() {
        String taskId = createTask("task-cycle-ab");
        List<FeatureItem> features = List.of(
                new FeatureItem("f1", "cat", "high", "desc", List.of("f2"), List.of("v"), false),
                new FeatureItem("f2", "cat", "high", "desc", List.of("f1"), List.of("v"), false));

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> store.writeInitialFeatureList(taskId, features));
        assertTrue(ex.getMessage().contains("Circular dependency"));
    }

    @Test
    void rejectsLongerCircularDependency() {
        String taskId = createTask("task-cycle-abc");
        List<FeatureItem> features = List.of(
                new FeatureItem("f1", "cat", "high", "desc", List.of("f2"), List.of("v"), false),
                new FeatureItem("f2", "cat", "high", "desc", List.of("f3"), List.of("v"), false),
                new FeatureItem("f3", "cat", "high", "desc", List.of("f1"), List.of("v"), false));

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> store.writeInitialFeatureList(taskId, features));
        assertTrue(ex.getMessage().contains("Circular dependency"));
    }

    // ---- markFeaturePassed dependency checks ----

    @Test
    void markFeaturePassedRejectsWhenDependencyNotPassed() {
        String taskId = createTask("task-dep-not-passed");
        List<FeatureItem> features = List.of(
                new FeatureItem("f1", "cat", "high", "base", List.of(), List.of("v"), false),
                new FeatureItem("f2", "cat", "high", "depends on f1", List.of("f1"), List.of("v"), false));
        store.writeInitialFeatureList(taskId, features);

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> store.markFeaturePassed(taskId, "f2"));
        assertTrue(ex.getMessage().contains("has not passed yet"));
    }

    @Test
    void markFeaturePassedSucceedsWhenDependencyPassed() {
        String taskId = createTask("task-dep-passed");
        List<FeatureItem> features = List.of(
                new FeatureItem("f1", "cat", "high", "base", List.of(), List.of("v"), false),
                new FeatureItem("f2", "cat", "high", "depends on f1", List.of("f1"), List.of("v"), false));
        store.writeInitialFeatureList(taskId, features);

        store.markFeaturePassed(taskId, "f1");
        FeatureItem result = store.markFeaturePassed(taskId, "f2");
        assertTrue(result.passes());
    }

    @Test
    void markFeaturePassedRejectsWhenActiveIssueExists() {
        String taskId = createTask("task-active-issue");
        List<FeatureItem> features = List.of(
                new FeatureItem("f1", "cat", "high", "desc", List.of(), List.of("v"), false));
        store.writeInitialFeatureList(taskId, features);

        store.recordIssue(taskId, new KnownIssue(
                "issue-1", "bug", "high", "open", "EXECUTING", List.of("v"),
                java.time.Instant.now(), null));

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> store.markFeaturePassed(taskId, "f1"));
        assertTrue(ex.getMessage().contains("known issues"));
    }

    // ---- Valid feature list with dependencies ----

    @Test
    void acceptsValidFeatureListWithDependencies() {
        String taskId = createTask("task-valid-deps");
        List<FeatureItem> features = List.of(
                new FeatureItem("f1", "cat", "high", "base", List.of(), List.of("v"), false),
                new FeatureItem("f2", "cat", "high", "depends on f1", List.of("f1"), List.of("v"), false));
        store.writeInitialFeatureList(taskId, features);

        List<FeatureItem> stored = store.readFeatureList(taskId);
        assertEquals(2, stored.size());
    }
}