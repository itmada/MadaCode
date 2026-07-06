package madacode.services.api;

import java.util.Optional;

/** Non-sensitive classification for a failed provider request. */
public record ApiFailureClassification(
        ApiErrorType type,
        boolean retryable,
        Integer statusCode,
        String message) {

    public ApiFailureClassification {
        if (type == null) {
            type = ApiErrorType.UNKNOWN;
        }
        message = message == null ? "" : message;
    }

    public static ApiFailureClassification classify(ApiClientException exception) {
        ApiError error = new ApiErrorClassifier().classify(exception);
        return new ApiFailureClassification(
                error.type(),
                error.retryable(),
                exception.statusCode(),
                error.message());
    }

    public static Optional<ApiFailureClassification> findIn(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof ApiClientException apiClientException) {
                return Optional.of(classify(apiClientException));
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    public boolean transientProviderFailure() {
        return retryable && switch (type) {
            case RATE_LIMIT, TIMEOUT, SERVER_ERROR, NETWORK -> true;
            case AUTH_FAILED, PROMPT_TOO_LONG, UNKNOWN -> false;
        };
    }

    public String detail() {
        return "apiFailure[type=" + type
                + ", retryable=" + retryable
                + ", status=" + (statusCode == null ? "(none)" : statusCode)
                + "]";
    }
}
