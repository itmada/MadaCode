package madacode.core;

public enum FinishReason {
    COMPLETED,
    MODEL_TRUNCATED,
    MAX_ITERATIONS,
    MAX_TOOL_CALLS,
    API_ERROR,
    CANCELLED,
    PERMISSION_CANCELLED
}
