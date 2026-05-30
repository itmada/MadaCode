package madacode.services.api;

/**
 * Runtime exception raised by API provider adapters.
 *
 * <p>The query engine owns turn lifecycle events, so providers should throw
 * this exception instead of emitting {@link madacode.core.model.MetaEvent.Error} directly.
 */
public class ApiClientException extends RuntimeException {

    private final Integer statusCode;
    private final String responseBody;

    public ApiClientException(String message) {
        this(message, null, null, null);
    }

    public ApiClientException(String message, Throwable cause) {
        this(message, cause, null, null);
    }

    public ApiClientException(
            String message,
            Throwable cause,
            Integer statusCode,
            String responseBody) {
        super(message, cause);
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public static ApiClientException http(int statusCode, String responseBody) {
        return new ApiClientException(
                "Anthropic API returned HTTP " + statusCode + ": " + responseBody,
                null,
                statusCode,
                responseBody);
    }

    public Integer statusCode() {
        return statusCode;
    }

    public String responseBody() {
        return responseBody;
    }
}
