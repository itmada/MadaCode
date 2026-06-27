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
    public static final PermissionLayer DEFAULT_LAYER = PermissionLayer.FALLBACK;

    private static final PermissionDecision ALLOWED =
            new PermissionDecision(true, null, DEFAULT_LAYER, SOURCE_UNSPECIFIED);

    private final boolean allowed;
    private final String reason;
    private final PermissionLayer layer;
    private final String source;

    private PermissionDecision(boolean allowed, String reason, PermissionLayer layer, String source) {
        this.allowed = allowed;
        this.reason = reason;
        this.layer = Objects.requireNonNull(layer, "layer");
        this.source = Objects.requireNonNull(source, "source");
    }

    public static PermissionDecision allow() {
        return ALLOWED;
    }

    public static PermissionDecision allow(String source) {
        return allow(DEFAULT_LAYER, source);
    }

    public static PermissionDecision allow(PermissionLayer layer, String source) {
        return new PermissionDecision(
                true,
                null,
                Objects.requireNonNull(layer, "layer"),
                Objects.requireNonNull(source, "source"));
    }

    public static PermissionDecision deny(String reason) {
        return deny(reason, DEFAULT_LAYER, SOURCE_UNSPECIFIED);
    }

    public static PermissionDecision deny(String reason, String source) {
        return deny(reason, DEFAULT_LAYER, source);
    }

    public static PermissionDecision deny(String reason, PermissionLayer layer, String source) {
        return new PermissionDecision(
                false,
                Objects.requireNonNull(reason, "reason is null"),
                Objects.requireNonNull(layer, "layer"),
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

    public PermissionLayer layer() {
        return layer;
    }

    /**
     * Machine-readable source of the decision, useful for logging/debugging.
     */
    public String source() {
        return source;
    }
}
