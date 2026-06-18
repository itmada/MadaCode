package madacode.longrunning;

import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    void controllerRequiresFeatureListBeforeStartingAndThenTransitionsDraftToRunning() {
        LongRunningController controller = new LongRunningController();
        ConversationSession session = controlSession();
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> controller.requestTransition(
                session,
                LongRunningStage.RUNNING,
                LongRunningTransitions.Trigger.USER_CONFIRMED_START.wire(),
                "Start execution",
                null,
                "tester"));

        assertTrue(error.getMessage().contains("feature_list.json"));
        assertEquals(LongRunningStage.DRAFT, session.longRunningStage());
        assertEquals("DRAFT", store.loadTask(session.longRunningTaskId()).status());

        store.writeInitialFeatureList(session.longRunningTaskId(), List.of(feature("feature-1")));
        LongRunningController.AppliedTransition transition = controller.requestAndApply(
                session,
                LongRunningStage.RUNNING,
                LongRunningTransitions.Trigger.USER_CONFIRMED_START.wire(),
                "Start execution",
                null,
                "tester",
                null);

        assertEquals(LongRunningStage.DRAFT, transition.sourceStage());
        assertEquals(LongRunningStage.RUNNING, transition.targetStage());
        assertEquals(LongRunningStage.RUNNING, session.longRunningStage());
        assertEquals("RUNNING", store.loadTask(session.longRunningTaskId()).status());
    }

    @Test
    void controllerInterruptsResumesAndCancelsThroughRealTaskStore() {
        LongRunningController controller = new LongRunningController();
        ConversationSession session = runningSession(controller);
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);

        LongRunningController.AppliedTransition interrupted = controller.requestAndApply(
                session,
                LongRunningStage.INTERRUPT,
                LongRunningTransitions.Trigger.WORKER_CYCLE_BUDGET_EXHAUSTED.wire(),
                "Worker hit its cycle budget",
                null,
                "launcher",
                null);

        assertEquals(LongRunningStage.INTERRUPT, interrupted.targetStage());
        assertEquals(LongRunningStage.INTERRUPT, session.longRunningStage());
        assertEquals("INTERRUPT", store.loadTask(session.longRunningTaskId()).status());

        LongRunningController.AppliedTransition resumed = controller.requestAndApply(
                session,
                LongRunningStage.RUNNING,
                LongRunningTransitions.Trigger.RESUME_AFTER_INTERRUPT.wire(),
                "Resume after review",
                null,
                "tester",
                null);

        assertEquals(LongRunningStage.RUNNING, resumed.targetStage());
        assertEquals(LongRunningStage.RUNNING, session.longRunningStage());
        assertEquals("RUNNING", store.loadTask(session.longRunningTaskId()).status());

        LongRunningController.AppliedTransition cancelled = controller.requestAndApply(
                session,
                LongRunningStage.CANCELLED,
                LongRunningTransitions.Trigger.USER_REQUESTED_CANCEL.wire(),
                "Stop the task",
                null,
                "tester",
                null);

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

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> controller.requestTransition(
                session,
                LongRunningStage.RUNNING,
                LongRunningTransitions.Trigger.RESUME_AFTER_INTERRUPT.wire(),
                "Resume without interrupt",
                null,
                "tester"));
        assertTrue(error.getMessage().contains("Transition not allowed"));
    }

    private ConversationSession runningSession(LongRunningController controller) {
        ConversationSession session = controlSession();
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);

        assertThrows(IllegalStateException.class, () -> controller.requestTransition(
                session,
                LongRunningStage.RUNNING,
                LongRunningTransitions.Trigger.USER_CONFIRMED_START.wire(),
                "Prime task state",
                null,
                "tester"));
        store.writeInitialFeatureList(session.longRunningTaskId(), List.of(feature("feature-1")));
        controller.requestAndApply(
                session,
                LongRunningStage.RUNNING,
                LongRunningTransitions.Trigger.USER_CONFIRMED_START.wire(),
                "Start execution",
                null,
                "tester",
                null);
        return session;
    }

    private ConversationSession controlSession() {
        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningPlanSummary("Implement the requested feature deterministically.");
        return session;
    }

    private static FeatureItem feature(String id) {
        return new FeatureItem(
                id,
                "implementation",
                "high",
                "Finish the requested work",
                List.of(),
                List.of("run deterministic verification"),
                false);
    }
}
