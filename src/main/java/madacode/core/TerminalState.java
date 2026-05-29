package madacode.core;

import java.util.Objects;

/**
 * Records why and how a turn ended. Carries three layers of detail:
 * <ul>
 *   <li>{@link TurnStatus} — coarse three-state outcome (DONE/FAILED/CANCELED)</li>
 *   <li>{@link TerminationCause} — specific cause within that outcome</li>
 *   <li>{@code reason} — human-readable string for logs / UI</li>
 * </ul>
 *
 * <p>This is the execution-layer terminal representation. It is the single
 * point that translates the business-layer {@link FinishReason} into the
 * persistent jsonl shape; see {@link #fromResult}.
 */
public record TerminalState(
        TurnStatus status,
        TerminationCause cause,
        String reason) {

    public static TerminalState done() {
        return new TerminalState(TurnStatus.DONE, TerminationCause.NORMAL, null);
    }

    public static TerminalState canceled(String reason) {
        return new TerminalState(TurnStatus.CANCELED, TerminationCause.CANCELED, reason);
    }

    public static TerminalState failed(String reason) {
        return new TerminalState(TurnStatus.FAILED, TerminationCause.RUNTIME_ERROR, reason);
    }

    public static TerminalState apiError(String reason) {
        return new TerminalState(TurnStatus.FAILED, TerminationCause.API_ERROR, reason);
    }

    public static TerminalState maxIterations(String reason) {
        return new TerminalState(TurnStatus.FAILED, TerminationCause.MAX_ITERATIONS, reason);
    }

    public static TerminalState modelTruncated(String reason) {
        return new TerminalState(TurnStatus.FAILED, TerminationCause.MODEL_TRUNCATED, reason);
    }

    public static TerminalState maxToolCalls(String reason) {
        return new TerminalState(TurnStatus.FAILED, TerminationCause.MAX_TOOL_CALLS, reason);
    }

    /**
     * Bridge from business-layer {@link FinishReason} to execution-layer
     * {@code TerminalState}. The single source of truth for that mapping —
     * adding a new {@code FinishReason} requires exactly one edit here.
     *
     * <p>{@code token} is consulted only for the {@code CANCELLED} arm, to
     * pick up the cancel reason that was supplied by whoever invoked
     * {@link CancellationToken#cancel(String)}.
     */
    public static TerminalState fromResult(TurnResult result, CancellationToken token) {
        Objects.requireNonNull(result, "result");
        return switch (result.finishReason()) {
            case COMPLETED      -> done();
            case CANCELLED, PERMISSION_CANCELLED
                                -> canceled(token != null && token.isCancelled()
                                            ? token.reason() : "cancelled");
            case API_ERROR      -> apiError(result.finalText());
            case MODEL_TRUNCATED -> modelTruncated(result.finalText());
            case MAX_ITERATIONS -> maxIterations(result.finalText());
            case MAX_TOOL_CALLS -> maxToolCalls(result.finalText());
        };
    }
}
