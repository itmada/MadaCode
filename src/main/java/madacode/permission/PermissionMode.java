package madacode.permission;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Permission policy for a conversation session.
 *
 * <p>Controls how the {@link PermissionGate} treats tools that would
 * otherwise prompt for user approval. Orthogonal to plan mode:
 * plan mode restricts which tools the LLM can invoke at all,
 * while PermissionMode controls how their approval flow runs.
 *
 * <p>Hierarchy of permissiveness (least → most):
 * DEFAULT < ACCEPT_EDITS < LONG_RUNNING_WORKSPACE < BYPASS.
 */
public enum PermissionMode {
    /** Every non-readonly tool requires explicit user approval. */
    DEFAULT("strict", "Prompt before any non-read-only tool", 0),

    /** File edit/write tools auto-pass; other writes still prompt.
     *  Default mode for sub-agents — they edit freely but bash/web calls
     *  still surface to the user. */
    ACCEPT_EDITS("normal",
            "Auto-allow file edits in the workspace; prompt for other writes", 1),

    /** Skip all interactive approval. Safety rules (e.g. dangerous bash)
     *  still apply — BYPASS only suppresses prompting, never overrides
     *  deny rules. */
    LONG_RUNNING_WORKSPACE("long-running-workspace",
            "Auto-allow file edits in the workspace; prompt for other writes", 2),
    BYPASS("all-pass",
            "Suppress interactive approval; structural safety rules still apply", 3);

    private final String id;
    private final String description;
    private final int permissivenessRank;

    PermissionMode(String id, String description, int permissivenessRank) {
        this.id = id;
        this.description = description;
        this.permissivenessRank = permissivenessRank;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    /**
     * Returns true if this mode is at least as permissive as {@code other}.
     * Ordering: DEFAULT &lt; ACCEPT_EDITS &lt; BYPASS.
     *
     * <p>Used by sub-agent inheritance: a sub-agent never gets a stricter
     * mode than its parent.
     */
    public boolean isAtLeastAsPermissiveAs(PermissionMode other) {
        return this.permissivenessRank >= other.permissivenessRank;
    }

    public static Optional<PermissionMode> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        return Arrays.stream(values())
                .filter(mode -> mode.id.equals(normalized))
                .findFirst();
    }
}
