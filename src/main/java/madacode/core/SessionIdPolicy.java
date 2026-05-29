package madacode.core;

import java.util.Objects;
import java.util.regex.Pattern;

final class SessionIdPolicy {

    private static final Pattern SAFE_TOKEN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    private SessionIdPolicy() {
    }

    static String validate(String sessionId) {
        Objects.requireNonNull(sessionId, "sessionId");
        if (sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (sessionId.contains("/") || sessionId.contains("\\") || sessionId.contains("..")) {
            throw new IllegalArgumentException("sessionId contains forbidden path characters");
        }
        if (!SAFE_TOKEN.matcher(sessionId).matches()) {
            throw new IllegalArgumentException("sessionId must be a UUID or safe token");
        }
        return sessionId;
    }
}
