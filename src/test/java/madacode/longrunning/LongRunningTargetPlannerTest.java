package madacode.longrunning;

import madacode.core.session.LongRunningTurnAssignment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LongRunningTargetPlannerTest {

    @TempDir
    Path tempDir;

    private LongRunningTaskStore store;
    private LongRunningTargetPlanner planner;

    @BeforeEach
    void setUp() {
        store = new LongRunningTaskStore(tempDir);
        planner = new LongRunningTargetPlanner(store);
    }

    private String createTask(String taskId) {
        store.createTask(new CreateTaskRequest(taskId, "Target planner", "executing", "session-1", "EXECUTING"));
        return taskId;
    }

    @Test
    void activeIssueTakesPriorityOverFeatures() {
        String taskId = createTask("task-target-issue");
        store.writeInitialFeatureList(taskId, List.of(
                new FeatureItem("feature-1", "backend", "high", "Build API", List.of(), List.of("test api"), false)));
        store.recordIssue(taskId, new KnownIssue(
                "issue-low",
                "Low issue",
                "low",
                "open",
                "EXECUTING",
                List.of("check low"),
                Instant.parse("2026-06-01T10:00:00Z"),
                null));
        store.recordIssue(taskId, new KnownIssue(
                "issue-high",
                "High issue",
                "high",
                "blocked",
                "EXECUTING",
                List.of("check high"),
                Instant.parse("2026-06-01T11:00:00Z"),
                null));

        LongRunningTurnAssignment assignment = planner.assign(taskId);

        assertEquals(LongRunningTurnAssignment.Kind.ISSUE, assignment.kind());
        assertEquals("issue-high", assignment.id());
        assertEquals("High issue", assignment.description());
        assertEquals(List.of("check high"), assignment.verificationSteps());
    }

    @Test
    void emptyFeatureListAssignsSeeding() {
        String taskId = createTask("task-target-seed");

        LongRunningTurnAssignment assignment = planner.assign(taskId);

        assertEquals(LongRunningTurnAssignment.Kind.SEED_FEATURE_LIST, assignment.kind());
    }

    @Test
    void selectsFirstIncompleteFeatureWithPassedDependencies() {
        String taskId = createTask("task-target-feature");
        store.writeInitialFeatureList(taskId, List.of(
                new FeatureItem("feature-1", "backend", "high", "Base", List.of(), List.of("base test"), false),
                new FeatureItem("feature-2", "backend", "high", "Depends", List.of("feature-1"), List.of("dep test"), false)));
        store.markFeaturePassed(taskId, "feature-1");

        LongRunningTurnAssignment assignment = planner.assign(taskId);

        assertEquals(LongRunningTurnAssignment.Kind.FEATURE, assignment.kind());
        assertEquals("feature-2", assignment.id());
        assertEquals(List.of("dep test"), assignment.verificationSteps());
    }

    @Test
    void allPassedAssignsTaskCompletion() {
        String taskId = createTask("task-target-complete");
        store.writeInitialFeatureList(taskId, List.of(
                new FeatureItem("feature-1", "backend", "high", "Base", List.of(), List.of("base test"), false)));
        store.markFeaturePassed(taskId, "feature-1");

        LongRunningTurnAssignment assignment = planner.assign(taskId);

        assertEquals(LongRunningTurnAssignment.Kind.COMPLETE_TASK, assignment.kind());
    }
}
