package madacode.permission;

import java.util.Objects;

/**
 * Result of a permission check performed by a {@link PermissionGate}.
 *
 * <p>Carries a binary allow/deny decision plus an optional human-readable
 * reason that is sent back to the model when the tool call is denied.
 *
 * <p>Use the static factories {@link #allow()} and {@link #deny(String)} to
 * create instances.
 */
public final class PermissionDecision {

    public static final String SOURCE_UNSPECIFIED = "unspecified";

    private static final PermissionDecision ALLOWED =
            new PermissionDecision(true, null, SOURCE_UNSPECIFIED);

    private final boolean allowed;
    private final String reason;
    private final String source;

    private PermissionDecision(boolean allowed, String reason, String source) {
        this.allowed = allowed;
        this.reason = reason;
        this.source = Objects.requireNonNull(source, "source");
    }

    public static PermissionDecision allow() {
        return ALLOWED;
    }

    public static PermissionDecision allow(String source) {
        return new PermissionDecision(true, null, Objects.requireNonNull(source, "source"));
    }

    public static PermissionDecision deny(String reason) {
        return deny(reason, SOURCE_UNSPECIFIED);
    }

    public static PermissionDecision deny(String reason, String source) {
        return new PermissionDecision(
                false,
                Objects.requireNonNull(reason, "reason is null"),
                Objects.requireNonNull(source, "source"));
    }

    public boolean isAllowed() {
        return allowed;
    }

    /**
     * Human-readable explanation of why the tool was denied.
     *
     * @return the denial reason, or {@code null} if the decision was to allow
     */
    public String reason() {
        return reason;
    }

    /**
     * Machine-readable source of the decision, useful for logging/debugging.
     */
    public String source() {
        return source;
    }
}
