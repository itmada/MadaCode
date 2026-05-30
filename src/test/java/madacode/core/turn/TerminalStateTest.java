package madacode.core.turn;

import madacode.core.model.*;
import madacode.core.session.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TerminalStateTest {

    @Test
    void fromResultMapsCompletedToDone() {
        TurnResult result = new TurnResult("ok", FinishReason.COMPLETED, 1);
        TerminalState ts = TerminalState.fromResult(result, CancellationToken.never());

        assertEquals(TurnStatus.DONE, ts.status());
        assertEquals(TerminationCause.NORMAL, ts.cause());
        assertNull(ts.reason());
    }

    @Test
    void fromResultMapsCancelledToCanceledWithTokenReason() {
        CancellationToken token = CancellationToken.create();
        token.cancel("esc");
        TurnResult result = new TurnResult("interrupted", FinishReason.CANCELLED, 2);
        TerminalState ts = TerminalState.fromResult(result, token);

        assertEquals(TurnStatus.CANCELED, ts.status());
        assertEquals(TerminationCause.CANCELED, ts.cause());
        assertEquals("esc", ts.reason());
    }

    @Test
    void fromResultMapsCancelledWithoutTokenCancelToFallback() {
        // Defensive: if QueryEngine reports CANCELLED but the token wasn't
        // actually flagged (shouldn't happen in practice), don't blow up.
        TurnResult result = new TurnResult("interrupted", FinishReason.CANCELLED, 2);
        TerminalState ts = TerminalState.fromResult(result, CancellationToken.never());

        assertEquals(TurnStatus.CANCELED, ts.status());
        assertEquals(TerminationCause.CANCELED, ts.cause());
        assertEquals("cancelled", ts.reason());
    }

    @Test
    void fromResultMapsApiErrorToFailedApiError() {
        TurnResult result = new TurnResult("API failed: 500", FinishReason.API_ERROR, 1);
        TerminalState ts = TerminalState.fromResult(result, CancellationToken.never());

        assertEquals(TurnStatus.FAILED, ts.status());
        assertEquals(TerminationCause.API_ERROR, ts.cause());
        assertEquals("API failed: 500", ts.reason());
    }

    @Test
    void fromResultMapsMaxIterationsToFailed() {
        TurnResult result = new TurnResult("hit iter ceiling", FinishReason.MAX_ITERATIONS, 50);
        TerminalState ts = TerminalState.fromResult(result, CancellationToken.never());

        assertEquals(TurnStatus.FAILED, ts.status());
        assertEquals(TerminationCause.MAX_ITERATIONS, ts.cause());
        assertEquals("hit iter ceiling", ts.reason());
    }

    @Test
    void fromResultMapsModelTruncatedToFailed() {
        TurnResult result = new TurnResult("partial", FinishReason.MODEL_TRUNCATED, 1);
        TerminalState ts = TerminalState.fromResult(result, CancellationToken.never());

        assertEquals(TurnStatus.FAILED, ts.status());
        assertEquals(TerminationCause.MODEL_TRUNCATED, ts.cause());
        assertEquals("partial", ts.reason());
    }

    @Test
    void fromResultMapsMaxToolCallsToFailed() {
        TurnResult result = new TurnResult("hit tool ceiling", FinishReason.MAX_TOOL_CALLS, 25);
        TerminalState ts = TerminalState.fromResult(result, CancellationToken.never());

        assertEquals(TurnStatus.FAILED, ts.status());
        assertEquals(TerminationCause.MAX_TOOL_CALLS, ts.cause());
        assertEquals("hit tool ceiling", ts.reason());
    }
}
