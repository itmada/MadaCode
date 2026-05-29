package madacode.permission;

/**
 * The user's response to a permission approval prompt.
 *
 * <p>Distinguishes between one-time and session-level approval so the
 * {@link DefaultPermissionGate} can remember the same tool input within a
 * session without persisting to disk.
 */
public enum ApprovalResponse {

    /** Allow this tool invocation once; ask again next time. */
    ALLOW_ONCE,

    /** Allow this exact tool input for the rest of the session. */
    ALLOW_SESSION,

    /** Deny this invocation. */
    DENY
}
