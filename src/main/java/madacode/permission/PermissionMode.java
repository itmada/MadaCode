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
 * DEFAULT < EDIT < LONG_RUNNING_WORKSPACE < BYPASS.
 */
public enum PermissionMode {
    /** Built-in non-edit tools and basic read/search bash commands auto-pass. */
    DEFAULT("default", "Auto-allow built-in non-edit tools and read/search bash", 0),

    /** Built-in file edit/write tools auto-pass; mutating bash still prompts. */
    EDIT("edit",
            "Auto-allow built-in file edits; prompt for mutating bash", 1),

    /** Skip all interactive approval. Safety rules (e.g. dangerous bash)
     *  still apply — BYPASS only suppresses prompting, never overrides
     *  deny rules. */
    LONG_RUNNING_WORKSPACE("long-running-workspace",
            "Auto-allow workspace edits and scoped worker bash", 2),
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
     * Whether a user may pick this mode interactively (e.g. via {@code /permission}).
     *
     * <p>{@link #LONG_RUNNING_WORKSPACE} is an internal sandbox applied
     * automatically to unattended long-running worker sessions; its
     * {@code LongRunningWorkspacePermissionRule} only takes effect on a worker
     * session, so selecting it in an interactive session would silently behave
     * like {@link #DEFAULT}. It is therefore hidden from user-facing selection
     * while remaining valid for {@link #parse(String)} (session persistence).
     */
    public boolean isUserSelectable() {
        return this != LONG_RUNNING_WORKSPACE;
    }

    /**
     * Returns true if this mode is at least as permissive as {@code other}.
     * Ordering: DEFAULT &lt; EDIT &lt; BYPASS.
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
