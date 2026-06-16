package madacode.longrunning;

import madacode.core.session.LongRunningStage;
import madacode.longrunning.LongRunningTransitions.InterruptCause;
import madacode.longrunning.LongRunningTransitions.TerminalOutcome;
import madacode.longrunning.LongRunningTransitions.Trigger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunningTransitionsTest {

    @Test
    void everyTriggerWireRoundTrips() {
        for (Trigger trigger : Trigger.values()) {
            assertEquals(trigger, Trigger.fromWire(trigger.wire()).orElseThrow(),
                    "wire round-trip for " + trigger);
        }
        assertTrue(Trigger.fromWire("not_a_reason").isEmpty());
        assertTrue(Trigger.fromWire(null).isEmpty());
        assertTrue(Trigger.fromWire("  ").isEmpty());
    }

    @Test
    void legalControllerEdgesAreAllowed() {
        assertTrue(LongRunningTransitions.isAllowed(
                LongRunningStage.DRAFT, Trigger.USER_CONFIRMED_START, LongRunningStage.RUNNING));
        assertTrue(LongRunningTransitions.isAllowed(
                LongRunningStage.RUNNING, Trigger.WORKER_BLOCKED, LongRunningStage.INTERRUPT));
        assertTrue(LongRunningTransitions.isAllowed(
                LongRunningStage.RUNNING, Trigger.TASK_COMPLETED, LongRunningStage.COMPLETED));
        assertTrue(LongRunningTransitions.isAllowed(
                LongRunningStage.INTERRUPT, Trigger.RESUME_AFTER_INTERRUPT, LongRunningStage.RUNNING));
        assertTrue(LongRunningTransitions.isAllowed(
                LongRunningStage.RUNNING, Trigger.WORKER_API_ERROR, LongRunningStage.INTERRUPT));
        // FAILURE is a user/controller terminal decision; worker/runtime failures interrupt.
        assertTrue(LongRunningTransitions.isAllowed(
                LongRunningStage.RUNNING, Trigger.FAILURE, LongRunningStage.FAILED));
        assertTrue(LongRunningTransitions.isAllowed(
                LongRunningStage.DRAFT, Trigger.USER_REQUESTED_CANCEL, LongRunningStage.CANCELLED));
        assertTrue(LongRunningTransitions.isAllowed(
                LongRunningStage.INTERRUPT, Trigger.USER_REQUESTED_CANCEL, LongRunningStage.CANCELLED));
    }

    @Test
    void illegalEdgesAreRejected() {
        // Terminal states have no outgoing edges.
        for (LongRunningStage from : LongRunningStage.values()) {
            if (!from.isTerminal()) {
                continue;
            }
            for (Trigger trigger : Trigger.values()) {
                for (LongRunningStage to : LongRunningStage.values()) {
                    assertFalse(LongRunningTransitions.isAllowed(from, trigger, to),
                            from + " must be terminal: " + trigger + " -> " + to);
                }
            }
        }
        // Workers cannot self-confirm a start from INTERRUPT, etc.
        assertFalse(LongRunningTransitions.isAllowed(
                LongRunningStage.DRAFT, Trigger.RESUME_AFTER_INTERRUPT, LongRunningStage.RUNNING));
        assertFalse(LongRunningTransitions.isAllowed(
                LongRunningStage.RUNNING, Trigger.USER_CONFIRMED_START, LongRunningStage.RUNNING));
        assertFalse(LongRunningTransitions.isAllowed(
                LongRunningStage.INTERRUPT, Trigger.TASK_COMPLETED, LongRunningStage.COMPLETED));
        assertFalse(LongRunningTransitions.isAllowed(
                LongRunningStage.RUNNING, Trigger.FAILURE, LongRunningStage.INTERRUPT));
    }

    @Test
    void terminalOutcomesCoverEveryTerminalTrigger() {
        assertEquals(TerminalOutcome.COMPLETED, LongRunningTransitions.terminalOutcomeFor(Trigger.TASK_COMPLETED));
        assertEquals(TerminalOutcome.CANCELLED, LongRunningTransitions.terminalOutcomeFor(Trigger.USER_REQUESTED_CANCEL));
        assertEquals(TerminalOutcome.FAILED, LongRunningTransitions.terminalOutcomeFor(Trigger.FAILURE));
        assertNull(LongRunningTransitions.terminalOutcomeFor(Trigger.WORKER_BLOCKED));
        // Any trigger with an edge into a terminal stage must have a terminal outcome.
        for (Trigger trigger : Trigger.values()) {
            boolean reachesTerminal = false;
            for (LongRunningStage from : LongRunningStage.values()) {
                for (LongRunningStage to : LongRunningStage.values()) {
                    reachesTerminal = reachesTerminal
                            || to.isTerminal()
                            && LongRunningTransitions.isAllowed(from, trigger, to);
                }
            }
            if (reachesTerminal) {
                assertTrue(LongRunningTransitions.terminalOutcomeFor(trigger) != null,
                        "trigger reaching a terminal stage needs a terminal outcome: " + trigger);
            }
        }
    }

    @Test
    void interruptCauseIsTypedPerTrigger() {
        assertEquals(InterruptCause.BLOCKED, LongRunningTransitions.causeFor(Trigger.WORKER_BLOCKED));
        assertEquals(InterruptCause.BUDGET, LongRunningTransitions.causeFor(Trigger.WORKER_CYCLE_BUDGET_EXHAUSTED));
        assertEquals(InterruptCause.CRASH, LongRunningTransitions.causeFor(Trigger.WORKER_CRASH));
        assertEquals(InterruptCause.NO_REPORT, LongRunningTransitions.causeFor(Trigger.WORKER_API_ERROR));
        assertEquals(InterruptCause.RUNTIME, LongRunningTransitions.causeFor(Trigger.RUNTIME_FAILED));
        assertEquals(InterruptCause.PROCESS_RESTARTED,
                LongRunningTransitions.causeForReason("process_restarted"));
        assertEquals(InterruptCause.OTHER, LongRunningTransitions.causeForReason("nonsense"));
    }

    @Test
    void targetCanBeDerivedFromSourceAndTrigger() {
        assertEquals(LongRunningStage.RUNNING, LongRunningTransitions.targetFor(
                LongRunningStage.DRAFT, Trigger.USER_CONFIRMED_START));
        assertEquals(LongRunningStage.INTERRUPT, LongRunningTransitions.targetFor(
                LongRunningStage.RUNNING, Trigger.WORKER_MODEL_TRUNCATED));
        assertEquals(LongRunningStage.CANCELLED, LongRunningTransitions.targetFor(
                LongRunningStage.INTERRUPT, Trigger.USER_REQUESTED_CANCEL));
        assertEquals(LongRunningStage.COMPLETED, LongRunningTransitions.targetFor(
                LongRunningStage.RUNNING, Trigger.TASK_COMPLETED));
        assertEquals(LongRunningStage.FAILED, LongRunningTransitions.targetFor(
                LongRunningStage.DRAFT, Trigger.FAILURE));
    }

    @Test
    void lifecycleStateMachineProducesTypedDecision() {
        LongRunningLifecycleDecision decision = LongRunningLifecycleStateMachine.decide(
                LongRunningStage.RUNNING,
                LongRunningLifecycleEvent.launcher(Trigger.WORKER_API_ERROR));

        assertEquals(LongRunningStage.RUNNING, decision.source());
        assertEquals(LongRunningStage.INTERRUPT, decision.target());
        assertEquals(Trigger.WORKER_API_ERROR, decision.trigger());
        assertEquals(InterruptCause.NO_REPORT, decision.interruptCause());
        assertNull(decision.terminalOutcome());
    }

    @Test
    void lifecycleStateMachineRejectsIllegalEvent() {
        assertThrows(IllegalStateException.class, () -> LongRunningLifecycleStateMachine.decide(
                LongRunningStage.COMPLETED,
                LongRunningLifecycleEvent.launcher(Trigger.WORKER_FAILED)));
    }

    @Test
    void requestableReasonsAreAllRealTriggers() {
        for (String wire : LongRunningTransitions.requestableReasonWires()) {
            assertTrue(Trigger.fromWire(wire).isPresent(), "requestable reason must be a trigger: " + wire);
        }
    }
}
