package madacode.core.session;

import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.model.MessageRole;
import madacode.permission.PermissionMode;
import madacode.plan.PlanItem;
import madacode.plan.PlanStatus;
import madacode.plan.TodoItem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadRoundTripsTranscriptState() {
        Path workingDirectory = tempDir.resolve("workspace");
        Instant createdAt = Instant.parse("2026-06-01T12:00:00Z");
        Instant taskCreatedAt = Instant.parse("2026-06-01T12:01:00Z");
        Instant taskUpdatedAt = Instant.parse("2026-06-01T12:02:00Z");
        Instant requestedAt = Instant.parse("2026-06-01T12:03:00Z");
        ConversationSession session = new ConversationSession(
                "roundtrip-session",
                createdAt,
                workingDirectory,
                List.of(
                        Message.system("Session initialized."),
                        Message.user("build the plan"),
                        Message.assistant(List.of(
                                new ContentBlock.TextBlock("I will plan it."),
                                new ContentBlock.ThinkingBlock("private reasoning omitted")))));
        PlanItem planItem = new PlanItem(
                "1",
                "Write feature baseline",
                "Capture current transcript behavior",
                PlanStatus.IN_PROGRESS,
                List.of("0"),
                taskCreatedAt,
                taskUpdatedAt,
                "writing-tests");
        LongRunningTransitionRequest transitionRequest = new LongRunningTransitionRequest(
                LongRunningStage.DRAFT,
                LongRunningStage.RUNNING,
                "plan_confirmed",
                "The plan is ready.",
                "No changes.",
                requestedAt,
                "user",
                false);

        session.plan().add(planItem);
        session.plan().replaceTodos(List.of(
                new TodoItem("Add session tests", "in_progress"),
                new TodoItem("Run verification", "pending")));
        session.addInput("first input");
        session.addInput("second input");
        session.setPlanMode(true);
        session.setPermissionMode(PermissionMode.BYPASS);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DRAFT);
        session.setLongRunningTaskId("task-123");
        session.setLongRunningTaskDirectory(workingDirectory.resolve("tasks/task-123").toString());
        session.setLongRunningTaskTitle("Durable session refactor");
        session.setLongRunningReason("Need durable state");
        session.setLongRunningPlanSummary("A concise plan summary");
        session.setLongRunningWorkerSession(true);
        session.setPendingLongRunningTransitionRequest(transitionRequest);
        session.loadDeferredTool("zeta_tool");
        session.loadDeferredTool("alpha_tool");

        SessionStorage storage = new SessionStorage(tempDir.resolve("sessions"));
        storage.save(session);

        ConversationSession restored = storage.load(session.sessionId());

        assertEquals(session.sessionId(), restored.sessionId());
        assertEquals(createdAt, restored.createdAt());
        assertEquals(workingDirectory.toAbsolutePath().normalize(), restored.workingDirectory());
        assertMessagesEqual(session.messages(), restored.messages());
        assertEquals(List.of(planItem), restored.plan().items());
        assertEquals(session.plan().todos(), restored.plan().todos());
        assertEquals(List.of("first input", "second input"), restored.inputHistory());
        assertEquals(SessionMode.LONG_RUNNING, restored.workflowMode());
        assertTrue(restored.isPlanMode());
        assertEquals(PermissionMode.BYPASS, restored.permissionMode());
        assertEquals(LongRunningStage.DRAFT, restored.longRunningStage());
        assertEquals("task-123", restored.longRunningTaskId());
        assertEquals(workingDirectory.resolve("tasks/task-123").toString(), restored.longRunningTaskDirectory());
        assertEquals("Durable session refactor", restored.longRunningTaskTitle());
        assertEquals("Need durable state", restored.longRunningReason());
        assertEquals("A concise plan summary", restored.longRunningPlanSummary());
        assertTrue(restored.isLongRunningWorkerSession());
        assertEquals(transitionRequest, restored.pendingLongRunningTransitionRequest().orElseThrow());
        assertEquals(session.loadedDeferredTools(), restored.loadedDeferredTools());
    }

    private static void assertMessagesEqual(List<Message> expected, List<Message> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).role(), actual.get(i).role());
            assertIterableEquals(expected.get(i).contentBlocks(), actual.get(i).contentBlocks());
        }
        assertEquals(MessageRole.USER, actual.get(1).role());
    }
}
