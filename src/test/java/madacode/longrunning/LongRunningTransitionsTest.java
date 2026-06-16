package madacode.longrunning;

import madacode.core.session.LongRunningStage;
import madacode.longrunning.LongRunningTransitions.InterruptCause;
import madacode.longrunning.LongRunningTransitions.TerminalAction;
import madacode.longrunning.LongRunningTransitions.Trigger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                LongRunningStage.RUNNING, Trigger.TASK_COMPLETED, LongRunningStage.DONE));
        assertTrue(LongRunningTransitions.isAllowed(
                LongRunningStage.INTERRUPT, Trigger.RESUME_AFTER_INTERRUPT, LongRunningStage.RUNNING));
        // FAILURE is legitimately overloaded: RUNNING can fail to INTERRUPT or DONE.
        assertTrue(LongRunningTransitions.isAllowed(
                LongRunningStage.RUNNING, Trigger.FAILURE, LongRunningStage.INTERRUPT));
        assertTrue(LongRunningTransitions.isAllowed(
                LongRunningStage.RUNNING, Trigger.FAILURE, LongRunningStage.DONE));
    }

    @Test
    void illegalEdgesAreRejected() {
        // DONE is terminal — no outgoing edges.
        for (Trigger trigger : Trigger.values()) {
            for (LongRunningStage to : LongRunningStage.values()) {
                assertFalse(LongRunningTransitions.isAllowed(LongRunningStage.DONE, trigger, to),
                        "DONE must be terminal: " + trigger + " -> " + to);
            }
        }
        // Workers cannot self-confirm a start from INTERRUPT, etc.
        assertFalse(LongRunningTransitions.isAllowed(
                LongRunningStage.DRAFT, Trigger.RESUME_AFTER_INTERRUPT, LongRunningStage.RUNNING));
        assertFalse(LongRunningTransitions.isAllowed(
                LongRunningStage.RUNNING, Trigger.USER_CONFIRMED_START, LongRunningStage.RUNNING));
        assertFalse(LongRunningTransitions.isAllowed(
                LongRunningStage.INTERRUPT, Trigger.TASK_COMPLETED, LongRunningStage.DONE));
    }

    @Test
    void terminalActionsCoverEveryDoneTrigger() {
        assertEquals(TerminalAction.COMPLETE, LongRunningTransitions.terminalActionFor(Trigger.TASK_COMPLETED));
        assertEquals(TerminalAction.CANCEL, LongRunningTransitions.terminalActionFor(Trigger.USER_REQUESTED_CANCEL));
        assertEquals(TerminalAction.FAIL, LongRunningTransitions.terminalActionFor(Trigger.FAILURE));
        assertNull(LongRunningTransitions.terminalActionFor(Trigger.WORKER_BLOCKED));
        // Any trigger with an edge into DONE must have a terminal action.
        for (Trigger trigger : Trigger.values()) {
            boolean reachesDone = LongRunningTransitions.isAllowed(
                    LongRunningStage.RUNNING, trigger, LongRunningStage.DONE)
                    || LongRunningTransitions.isAllowed(
                    LongRunningStage.INTERRUPT, trigger, LongRunningStage.DONE)
                    || LongRunningTransitions.isAllowed(
                    LongRunningStage.DRAFT, trigger, LongRunningStage.DONE);
            if (reachesDone) {
                assertTrue(LongRunningTransitions.terminalActionFor(trigger) != null,
                        "trigger reaching DONE needs a terminal action: " + trigger);
            }
        }
    }

    @Test
    void interruptCauseIsTypedPerTrigger() {
        assertEquals(InterruptCause.BLOCKED, LongRunningTransitions.causeFor(Trigger.WORKER_BLOCKED));
        assertEquals(InterruptCause.BUDGET, LongRunningTransitions.causeFor(Trigger.WORKER_CYCLE_BUDGET_EXHAUSTED));
        assertEquals(InterruptCause.CRASH, LongRunningTransitions.causeFor(Trigger.WORKER_CRASH));
        assertEquals(InterruptCause.PROCESS_RESTARTED,
                LongRunningTransitions.causeForReason("process_restarted"));
        assertEquals(InterruptCause.OTHER, LongRunningTransitions.causeForReason("nonsense"));
    }

    @Test
    void requestableReasonsAreAllRealTriggers() {
        for (String wire : LongRunningTransitions.requestableReasonWires()) {
            assertTrue(Trigger.fromWire(wire).isPresent(), "requestable reason must be a trigger: " + wire);
        }
    }
}
