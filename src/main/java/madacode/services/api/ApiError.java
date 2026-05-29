package madacode.services.api;

public record ApiError(
        ApiErrorType type,
        String message,
        boolean retryable) {
}
