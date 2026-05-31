package madacode.core.session;

import madacode.permission.PermissionMode;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * User-facing mode presets for the REPL.
 *
 * <p>The runtime keeps permission policy and plan-mode restrictions as
 * separate axes. This enum names the combinations exposed through /mode
 * without collapsing those lower-level concerns.
 */
public enum SessionMode {
    STRICT("strict", "Prompt before any non-read-only tool", PermissionMode.DEFAULT, false),
    NORMAL("normal", "Auto-allow file edits in the workspace; prompt for other writes",
            PermissionMode.ACCEPT_EDITS, false),
    PLAN("plan", "Read-only exploration and planning until plan approval",
            PermissionMode.DEFAULT, true),
    ALL_PASS("all-pass", "Suppress interactive approval; structural safety rules still apply",
            PermissionMode.BYPASS, false);

    private final String id;
    private final String description;
    private final PermissionMode permissionMode;
    private final boolean planMode;

    SessionMode(String id, String description, PermissionMode permissionMode, boolean planMode) {
        this.id = id;
        this.description = description;
        this.permissionMode = permissionMode;
        this.planMode = planMode;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public PermissionMode permissionMode() {
        return permissionMode;
    }

    public boolean planMode() {
        return planMode;
    }

    public void applyTo(ConversationSession session) {
        session.setPermissionMode(permissionMode);
        session.setPlanMode(planMode);
    }

    public static SessionMode from(ConversationSession session) {
        if (session.isPlanMode()) {
            return PLAN;
        }
        return switch (session.permissionMode()) {
            case DEFAULT -> STRICT;
            case ACCEPT_EDITS -> NORMAL;
            case BYPASS -> ALL_PASS;
        };
    }

    public static Optional<SessionMode> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        return Arrays.stream(values())
                .filter(mode -> mode.id.equals(normalized))
                .findFirst();
    }
}
