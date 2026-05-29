package madacode.services.api;

public enum ApiErrorType {
    RATE_LIMIT,
    TIMEOUT,
    AUTH_FAILED,
    PROMPT_TOO_LONG,
    SERVER_ERROR,
    NETWORK,
    UNKNOWN
}
