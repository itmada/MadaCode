package madacode.services.api;

import java.io.IOException;
import java.net.http.HttpTimeoutException;

public final class ApiErrorClassifier {

    public ApiError classify(ApiClientException exception) {
        Integer status = exception.statusCode();
        String body = exception.responseBody() == null
                ? ""
                : exception.responseBody().toLowerCase();
        Throwable cause = exception.getCause();

        if (status != null) {
            return classifyHttp(status, body, exception.getMessage());
        }

        if (cause instanceof HttpTimeoutException) {
            return new ApiError(ApiErrorType.TIMEOUT, exception.getMessage(), true);
        }

        if (hasNetworkCause(cause)) {
            return new ApiError(ApiErrorType.NETWORK, exception.getMessage(), true);
        }

        return new ApiError(ApiErrorType.UNKNOWN, exception.getMessage(), false);
    }

    private ApiError classifyHttp(int status, String body, String message) {
        if (status == 429) {
            return new ApiError(ApiErrorType.RATE_LIMIT, message, true);
        }
        if (status == 401 || status == 403) {
            return new ApiError(ApiErrorType.AUTH_FAILED, message, false);
        }
        if (status == 400
                && (body.contains("prompt")
                || body.contains("max_tokens")
                || body.contains("too long"))) {
            return new ApiError(ApiErrorType.PROMPT_TOO_LONG, message, false);
        }
        if (status == 529 || status == 500 || status == 502 || status == 503 || status == 504) {
            return new ApiError(ApiErrorType.SERVER_ERROR, message, true);
        }
        return new ApiError(ApiErrorType.UNKNOWN, message, false);
    }

    private boolean hasNetworkCause(Throwable cause) {
        while (cause != null) {
            if (cause instanceof IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
