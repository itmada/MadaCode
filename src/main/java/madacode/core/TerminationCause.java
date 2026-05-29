package madacode.core;

/**
 * Why a turn terminated. Distinct from {@link TurnStatus} (which is the
 * coarse three-state: DONE/FAILED/CANCELED): a single status can correspond
 * to several causes, and they have different operational meanings.
 *
 * <p>Cause is the execution-layer view. The business-layer equivalent is
 * {@link FinishReason}; the mapping lives in
 * {@link TerminalState#fromResult(TurnResult, CancellationToken)}.
 */
public enum TerminationCause {
    /** Turn ran to completion. */
    NORMAL,
    /** Turn stopped because someone called cancel — user keystroke, signal, or programmatic. */
    CANCELED,
    /** Model loop hit the configured iteration ceiling. */
    MAX_ITERATIONS,
    /** Model response was truncated by the upstream max_tokens limit. */
    MODEL_TRUNCATED,
    /** Model loop hit the configured tool-call ceiling. */
    MAX_TOOL_CALLS,
    /** An exception escaped the runner that wasn't already classified. */
    RUNTIME_ERROR,
    /** Upstream API failed in a way the runner handled gracefully (returned, didn't throw). */
    API_ERROR
}
