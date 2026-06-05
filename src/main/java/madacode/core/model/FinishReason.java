package madacode.core.model;

public enum FinishReason {
    COMPLETED,
    MODEL_TRUNCATED,
    MAX_ITERATIONS,
    API_ERROR,
    CANCELLED,
    PERMISSION_CANCELLED
}
