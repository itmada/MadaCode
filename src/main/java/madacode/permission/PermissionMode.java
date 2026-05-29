package madacode.permission;

/**
 * Permission policy for a conversation session.
 *
 * <p>Controls how the {@link PermissionGate} treats tools that would
 * otherwise prompt for user approval. Orthogonal to plan mode:
 * plan mode restricts which tools the LLM can invoke at all,
 * while PermissionMode controls how their approval flow runs.
 *
 * <p>Hierarchy of permissiveness (least → most):
 * DEFAULT < ACCEPT_EDITS < BYPASS.
 */
public enum PermissionMode {
    /** Every non-readonly tool requires explicit user approval. */
    DEFAULT(0),

    /** File edit/write tools auto-pass; other writes still prompt.
     *  Default mode for sub-agents — they edit freely but bash/web calls
     *  still surface to the user. */
    ACCEPT_EDITS(1),

    /** Skip all interactive approval. Safety rules (e.g. dangerous bash)
     *  still apply — BYPASS only suppresses prompting, never overrides
     *  deny rules. */
    BYPASS(2);

    private final int permissivenessRank;

    PermissionMode(int permissivenessRank) {
        this.permissivenessRank = permissivenessRank;
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
}
