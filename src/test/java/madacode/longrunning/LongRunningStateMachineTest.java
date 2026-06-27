package madacode.longrunning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.core.model.ToolResult;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.tool.LongRunEnvironmentUpdateTool;
import madacode.tool.validation.ToolInputCoercion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunningStateMachineTest {

    @TempDir
    Path tempDir;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void controllerRequiresInitializedEnvironmentBeforeTransitionProposal() {
        LongRunningController controller = new LongRunningController();
        ConversationSession session = bareControlSession();

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> controller.prepareTransition(
                session,
                LongRunningStage.RUNNING,
                LongRunningTransitions.Trigger.USER_CONFIRMED_START.wire(),
                "Start execution",
                null,
                "tester"));

        assertTrue(error.getMessage().contains("environment files are not initialized"));
        assertEquals(LongRunningStage.DRAFT, session.longRunningStage());
    }

    @Test
    void controllerTransitionsDraftToRunningAfterEnvironmentInitialization() {
        LongRunningController controller = new LongRunningController();
        ConversationSession session = initializedControlSession();
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);

        String planSummary = store.loadTask(session.longRunningTaskId()).planSummary();
        LongRunningController.AppliedTransition transition = controller.prepareAndApply(
                session,
                LongRunningStage.RUNNING,
                LongRunningTransitions.Trigger.USER_CONFIRMED_START.wire(),
                "Start execution",
                null,
                "tester");

        assertEquals(LongRunningStage.DRAFT, transition.sourceStage());
        assertEquals(LongRunningStage.RUNNING, transition.targetStage());
        assertEquals(LongRunningStage.RUNNING, session.longRunningStage());
        assertEquals("RUNNING", store.loadTask(session.longRunningTaskId()).status());
        assertEquals(planSummary, store.loadTask(session.longRunningTaskId()).planSummary());
    }

    @Test
    void controllerReadinessStillRejectsEmptyFeatureListOnInitializedTask() {
        LongRunningController controller = new LongRunningController();
        ConversationSession session = initializedControlSession();
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);

        store.replaceFeatureList(session.longRunningTaskId(), List.of());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> controller.prepareTransition(
                session,
                LongRunningStage.RUNNING,
                LongRunningTransitions.Trigger.USER_CONFIRMED_START.wire(),
                "Start execution",
                null,
                "tester"));

        assertTrue(error.getMessage().contains("long-running environment feature list is empty"));
        assertEquals(LongRunningStage.DRAFT, session.longRunningStage());
        assertEquals("DRAFT", store.loadTask(session.longRunningTaskId()).status());
    }

    @Test
    void controllerReportsMalformedFeatureListReadinessFailure() throws Exception {
        LongRunningController controller = new LongRunningController();
        ConversationSession session = initializedControlSession();
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);

        Path featureList = store.taskDirectoryPath(session.longRunningTaskId())
                .resolve(LongRunningTaskRepository.FEATURE_LIST_FILE);
        Files.writeString(featureList, """
                [
                  {
                    "category": "implementation",
                    "priority": "high",
                    "description": "Finish the requested work",
                    "depends_on": [],
                    "verification_steps": ["run deterministic verification"],
                    "passes": false
                  }
                ]
                """);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> controller.prepareTransition(
                session,
                LongRunningStage.RUNNING,
                LongRunningTransitions.Trigger.USER_CONFIRMED_START.wire(),
                "Start execution",
                null,
                "tester"));

        assertTrue(error.getMessage().contains("long-running environment feature list is malformed"));
        assertTrue(error.getMessage().contains("Missing required field: id"));
    }

    @Test
    void controllerInterruptsResumesAndCancelsThroughRealTaskStore() {
        LongRunningController controller = new LongRunningController();
        ConversationSession session = runningSession(controller);
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);

        LongRunningController.AppliedTransition interrupted = controller.prepareAndApply(
                session,
                LongRunningStage.INTERRUPT,
                LongRunningTransitions.Trigger.WORKER_CYCLE_BUDGET_EXHAUSTED.wire(),
                "Worker hit its cycle budget",
                null,
                "launcher");

        assertEquals(LongRunningStage.INTERRUPT, interrupted.targetStage());
        assertEquals(LongRunningStage.INTERRUPT, session.longRunningStage());
        assertEquals("INTERRUPT", store.loadTask(session.longRunningTaskId()).status());

        LongRunningController.AppliedTransition resumed = controller.prepareAndApply(
                session,
                LongRunningStage.RUNNING,
                LongRunningTransitions.Trigger.RESUME_AFTER_INTERRUPT.wire(),
                "Resume after review",
                null,
                "tester");

        assertEquals(LongRunningStage.RUNNING, resumed.targetStage());
        assertEquals(LongRunningStage.RUNNING, session.longRunningStage());
        assertEquals("RUNNING", store.loadTask(session.longRunningTaskId()).status());

        LongRunningController.AppliedTransition cancelled = controller.prepareAndApply(
                session,
                LongRunningStage.CANCELLED,
                LongRunningTransitions.Trigger.USER_REQUESTED_CANCEL.wire(),
                "Stop the task",
                null,
                "tester");

        assertEquals(LongRunningStage.CANCELLED, cancelled.targetStage());
        assertEquals(LongRunningStage.CANCELLED, session.longRunningStage());
        assertEquals("CANCELLED", store.loadTask(session.longRunningTaskId()).status());
    }

    @Test
    void runtimeInterruptsStayNonTerminalWhileFailureBecomesTerminalAndIllegalResumeIsRejected() {
        LongRunningController controller = new LongRunningController();
        ConversationSession session = runningSession(controller);

        LongRunningLifecycleDecision runtimeFailure = LongRunningLifecycleStateMachine.decide(
                LongRunningStage.RUNNING,
                LongRunningLifecycleEvent.runtime(LongRunningTransitions.Trigger.RUNTIME_FAILED));
        LongRunningLifecycleDecision terminalFailure = LongRunningLifecycleStateMachine.decide(
                LongRunningStage.INTERRUPT,
                LongRunningLifecycleEvent.controller(LongRunningTransitions.Trigger.FAILURE));

        assertEquals(LongRunningStage.INTERRUPT, runtimeFailure.target());
        assertFalse(runtimeFailure.isTerminal());
        assertEquals(LongRunningTransitions.InterruptCause.RUNTIME, runtimeFailure.interruptCause());
        assertNull(runtimeFailure.terminalOutcome());

        assertEquals(LongRunningStage.FAILED, terminalFailure.target());
        assertTrue(terminalFailure.isTerminal());
        assertEquals(LongRunningTransitions.TerminalOutcome.FAILED, terminalFailure.terminalOutcome());

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> controller.prepareTransition(
                session,
                LongRunningStage.RUNNING,
                LongRunningTransitions.Trigger.RESUME_AFTER_INTERRUPT.wire(),
                "Resume without interrupt",
                null,
                "tester"));
        assertTrue(error.getMessage().contains("Transition not allowed"));
    }

    private ConversationSession runningSession(LongRunningController controller) {
        ConversationSession session = initializedControlSession();

        controller.prepareAndApply(
                session,
                LongRunningStage.RUNNING,
                LongRunningTransitions.Trigger.USER_CONFIRMED_START.wire(),
                "Start execution",
                null,
                "tester");
        return session;
    }

    private ConversationSession bareControlSession() {
        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DRAFT);
        return session;
    }

    private ConversationSession initializedControlSession() {
        ConversationSession session = bareControlSession();
        LongRunEnvironmentUpdateTool tool = new LongRunEnvironmentUpdateTool();
        ToolResult result = tool.execute(
                ToolInputCoercion.coerce(tool, initializeEnvironmentInput(), mapper),
                new ToolUseContext(tempDir, session));
        assertTrue(result.success(), result.output());
        return session;
    }

    private ObjectNode initializeEnvironmentInput() {
        ObjectNode input = mapper.createObjectNode();
        input.put("action", "initialize_environment");
        input.put("title", "Deterministic long-running test");
        input.put("plan_summary", "Implement the requested feature deterministically.");
        input.put("text", "Environment initialized for state-machine test.");
        ArrayNode features = input.putArray("features");
        ObjectNode feature = features.addObject();
        feature.put("id", "feature-1");
        feature.put("category", "implementation");
        feature.put("priority", "high");
        feature.put("description", "Finish the requested work");
        feature.putArray("depends_on");
        feature.putArray("verification_steps").add("run deterministic verification");
        input.putArray("issues");
        return input;
    }
}
