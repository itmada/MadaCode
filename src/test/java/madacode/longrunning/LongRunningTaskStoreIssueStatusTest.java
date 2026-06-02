package madacode.longrunning;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunningTaskStoreIssueStatusTest {

    @TempDir
    Path tempDir;

    private LongRunningTaskStore store;

    @BeforeEach
    void setUp() {
        store = new LongRunningTaskStore(tempDir);
    }

    private String createTaskWithFeatures(String taskId) {
        store.createTask(new CreateTaskRequest(taskId, "Test task", "executing", "session-1", "EXECUTING"));
        store.writeInitialFeatureList(taskId, List.of(
                new FeatureItem("f1", "cat", "high", "desc", List.of(), List.of("v"), false)));
        return taskId;
    }

    // ---- open -> blocked ----

    @Test
    void openToBlockedTransition() {
        String taskId = createTaskWithFeatures("task-ob");
        store.recordIssue(taskId, new KnownIssue(
                "i1", "desc", "high", "open", "EXECUTING", List.of(), Instant.now(), null));

        KnownIssue updated = store.updateIssueStatus(taskId, "i1", "blocked");
        assertEquals("blocked", updated.status());
        assertEquals(null, updated.resolvedAt());
    }

    // ---- blocked -> open ----

    @Test
    void blockedToOpenTransition() {
        String taskId = createTaskWithFeatures("task-bo");
        store.recordIssue(taskId, new KnownIssue(
                "i1", "desc", "high", "blocked", "EXECUTING", List.of(), Instant.now(), null));

        KnownIssue updated = store.updateIssueStatus(taskId, "i1", "open");
        assertEquals("open", updated.status());
        assertEquals(null, updated.resolvedAt());
    }

    // ---- open -> resolved ----

    @Test
    void openToResolvedTransition() {
        String taskId = createTaskWithFeatures("task-or");
        store.recordIssue(taskId, new KnownIssue(
                "i1", "desc", "high", "open", "EXECUTING", List.of(), Instant.now(), null));

        KnownIssue updated = store.updateIssueStatus(taskId, "i1", "resolved");
        assertEquals("resolved", updated.status());
        assertNotNull(updated.resolvedAt());
    }

    // ---- blocked -> resolved ----

    @Test
    void blockedToResolvedTransition() {
        String taskId = createTaskWithFeatures("task-br");
        store.recordIssue(taskId, new KnownIssue(
                "i1", "desc", "high", "blocked", "EXECUTING", List.of(), Instant.now(), null));

        KnownIssue updated = store.updateIssueStatus(taskId, "i1", "resolved");
        assertEquals("resolved", updated.status());
        assertNotNull(updated.resolvedAt());
    }

    // ---- resolved -> open (rejected) ----

    @Test
    void resolvedToOpenTransitionRejected() {
        String taskId = createTaskWithFeatures("task-ro");
        store.recordIssue(taskId, new KnownIssue(
                "i1", "desc", "high", "open", "EXECUTING", List.of(), Instant.now(), null));
        store.updateIssueStatus(taskId, "i1", "resolved");

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> store.updateIssueStatus(taskId, "i1", "open"));
        assertTrue(ex.getMessage().contains("resolved"));
    }

    // ---- resolved -> blocked (rejected) ----

    @Test
    void resolvedToBlockedTransitionRejected() {
        String taskId = createTaskWithFeatures("task-rb");
        store.recordIssue(taskId, new KnownIssue(
                "i1", "desc", "high", "open", "EXECUTING", List.of(), Instant.now(), null));
        store.updateIssueStatus(taskId, "i1", "resolved");

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> store.updateIssueStatus(taskId, "i1", "blocked"));
        assertTrue(ex.getMessage().contains("resolved"));
    }

    // ---- Same status is no-op ----

    @Test
    void sameStatusIsNoOp() {
        String taskId = createTaskWithFeatures("task-same");
        store.recordIssue(taskId, new KnownIssue(
                "i1", "desc", "high", "open", "EXECUTING", List.of(), Instant.now(), null));

        KnownIssue updated = store.updateIssueStatus(taskId, "i1", "open");
        assertEquals("open", updated.status());
        assertEquals(null, updated.resolvedAt());
    }

    // ---- resolvedAt set and cleared correctly ----

    @Test
    void resolvedAtSetOnResolveClearedOnReopen() {
        String taskId = createTaskWithFeatures("task-rat");
        store.recordIssue(taskId, new KnownIssue(
                "i1", "desc", "high", "open", "EXECUTING", List.of(), Instant.now(), null));

        // open -> blocked: no resolvedAt
        KnownIssue blocked = store.updateIssueStatus(taskId, "i1", "blocked");
        assertEquals(null, blocked.resolvedAt());

        // blocked -> resolved: resolvedAt is set
        KnownIssue resolved = store.updateIssueStatus(taskId, "i1", "resolved");
        assertNotNull(resolved.resolvedAt());
    }

    // ---- Unknown issue ----

    @Test
    void updateStatusRejectsUnknownIssue() {
        String taskId = createTaskWithFeatures("task-unknown");

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> store.updateIssueStatus(taskId, "nonexistent", "open"));
        assertTrue(ex.getMessage().contains("Unknown issue"));
    }

    // ---- Invalid status ----

    @Test
    void updateStatusRejectsInvalidStatus() {
        String taskId = createTaskWithFeatures("task-invalid");
        store.recordIssue(taskId, new KnownIssue(
                "i1", "desc", "high", "open", "EXECUTING", List.of(), Instant.now(), null));

        LongRunningTaskStoreException ex = assertThrows(LongRunningTaskStoreException.class,
                () -> store.updateIssueStatus(taskId, "i1", "invalid_status"));
        assertTrue(ex.getMessage().contains("Unsupported"));
    }
}