package madacode.longrunning;

import madacode.core.session.LongRunningTurnAssignment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunningPostTurnVerifierTest {

    @TempDir
    Path tempDir;

    private LongRunningTaskStore store;
    private LongRunningPostTurnVerifier verifier;

    @BeforeEach
    void setUp() {
        store = new LongRunningTaskStore(tempDir);
        verifier = new LongRunningPostTurnVerifier(store);
    }

    private String createTask(String taskId) {
        store.createTask(new CreateTaskRequest(taskId, "Verifier", "executing", "session-1", "EXECUTING"));
        return taskId;
    }

    @Test
    void seedFeatureListFailsWhenFeatureListRemainsEmpty() throws Exception {
        String taskId = createTask("task-verify-seed");
        LongRunningTurnAssignment assignment = new LongRunningTurnAssignment(
                LongRunningTurnAssignment.Kind.SEED_FEATURE_LIST,
                null,
                "Seed features",
                "empty list",
                List.of());
        appendAssignment(taskId, assignment);

        var result = verifier.verify(taskId, "session-1", assignment);

        assertFalse(result.success());
        assertTrue(Files.readString(tempDir.resolve(".mada/long-running/task-verify-seed/progress.txt"))
                .contains("HARNESS WARNING"));
        assertTrue(store.readEvents(taskId).stream()
                .anyMatch(event -> "assignment_verified".equals(event.type())
                        && Boolean.FALSE.equals(event.success())));
    }

    @Test
    void issueAssignmentSucceedsWhenIssueResolved() {
        String taskId = createTask("task-verify-issue");
        store.recordIssue(taskId, new KnownIssue(
                "issue-1", "Fix bug", "high", "open", "EXECUTING", List.of(),
                Instant.parse("2026-06-01T00:00:00Z"), null));
        LongRunningTurnAssignment assignment = new LongRunningTurnAssignment(
                LongRunningTurnAssignment.Kind.ISSUE,
                "issue-1",
                "Fix bug",
                "issue first",
                List.of());
        appendAssignment(taskId, assignment);
        store.markIssueResolved(taskId, "issue-1");
        store.appendEvent(taskId, LongRunningTaskEvent.of(
                "task_update", taskId, "session-1", "EXECUTING", "resolve_issue", true,
                "Known issue resolved.", Map.of("issueId", "issue-1")));

        var result = verifier.verify(taskId, "session-1", assignment);

        assertTrue(result.success());
    }

    @Test
    void featureAssignmentFailsWithoutTaskUpdate() {
        String taskId = createTask("task-verify-feature-fail");
        store.writeInitialFeatureList(taskId, List.of(
                new FeatureItem("feature-1", "backend", "high", "Build", List.of(), List.of(), false)));
        LongRunningTurnAssignment assignment = new LongRunningTurnAssignment(
                LongRunningTurnAssignment.Kind.FEATURE,
                "feature-1",
                "Build",
                "eligible",
                List.of());
        appendAssignment(taskId, assignment);

        var result = verifier.verify(taskId, "session-1", assignment);

        assertFalse(result.success());
    }

    @Test
    void featureAssignmentSucceedsWithTaskUpdateProgress() {
        String taskId = createTask("task-verify-feature-progress");
        store.writeInitialFeatureList(taskId, List.of(
                new FeatureItem("feature-1", "backend", "high", "Build", List.of(), List.of(), false)));
        LongRunningTurnAssignment assignment = new LongRunningTurnAssignment(
                LongRunningTurnAssignment.Kind.FEATURE,
                "feature-1",
                "Build",
                "eligible",
                List.of());
        appendAssignment(taskId, assignment);
        store.appendEvent(taskId, LongRunningTaskEvent.of(
                "task_update", taskId, "session-1", "EXECUTING", "append_progress", true,
                "Progress appended.", Map.of(
                        "assignedKind", "FEATURE",
                        "assignedTargetId", "feature-1")));

        var result = verifier.verify(taskId, "session-1", assignment);

        assertTrue(result.success());
    }

    @Test
    void featureAssignmentFailsWhenTaskUpdateTargetsDifferentFeature() {
        String taskId = createTask("task-verify-feature-drift");
        store.writeInitialFeatureList(taskId, List.of(
                new FeatureItem("feature-1", "backend", "high", "Build", List.of(), List.of(), false),
                new FeatureItem("feature-2", "backend", "medium", "Other", List.of(), List.of(), false)));
        LongRunningTurnAssignment assignment = new LongRunningTurnAssignment(
                LongRunningTurnAssignment.Kind.FEATURE,
                "feature-1",
                "Build",
                "eligible",
                List.of());
        appendAssignment(taskId, assignment);
        store.appendEvent(taskId, LongRunningTaskEvent.of(
                "task_update", taskId, "session-1", "EXECUTING", "append_progress", true,
                "Progress appended.", Map.of(
                        "assignedKind", "FEATURE",
                        "assignedTargetId", "feature-2")));

        var result = verifier.verify(taskId, "session-1", assignment);

        assertFalse(result.success());
    }

    private void appendAssignment(String taskId, LongRunningTurnAssignment assignment) {
        store.appendEvent(taskId, LongRunningTaskEvent.of(
                "target_assigned",
                taskId,
                "session-1",
                "EXECUTING",
                assignment.kind().name(),
                true,
                assignment.description(),
                Map.of("targetId", assignment.id() == null ? "" : assignment.id())));
    }
}
