package madacode.core;

/**
 * Why the model stopped generating in a single API call.
 *
 * <p>Mirrors the {@code stop_reason} field on the Anthropic Messages API
 * {@code message_delta} event. Distinct from
 * {@link FinishReason}, which describes why the whole agent <em>turn</em>
 * ended (turns may span multiple API calls).
 */
public enum StopReason {
    /** Model finished its response naturally. */
    END_TURN,
    /** Model ended the message with a tool_use block (agent loop continues). */
    TOOL_USE,
    /** Model hit the max_tokens limit before finishing. */
    MAX_TOKENS_REACHED,
    /** Model produced a configured stop sequence. */
    STOP_SEQUENCE,
    /** Safety classifier refused; assistant text contains the refusal message. */
    REFUSAL,
    /** Stop reason absent from the stream or unrecognised by this client. */
    UNKNOWN;

    public static StopReason parse(String wireValue) {
        if (wireValue == null || wireValue.isBlank()) {
            return UNKNOWN;
        }
        return switch (wireValue) {
            case "end_turn" -> END_TURN;
            case "tool_use" -> TOOL_USE;
            case "max_tokens" -> MAX_TOKENS_REACHED;
            case "stop_sequence" -> STOP_SEQUENCE;
            case "refusal" -> REFUSAL;
            default -> UNKNOWN;
        };
    }
}
