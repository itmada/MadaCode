package madacode.core.session;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * User-facing workflow modes for the REPL.
 *
 * <p>Workflow mode is independent from plan-mode tools and the permission
 * policy. The runtime keeps those axes separate so /mode can express the
 * user's working style without implicitly re-encoding every permission state.
 */
public enum SessionMode {
    COMMON("common", "Standard interactive workflow for everyday tasks"),
    LONG_RUNNING("long-running",
            "Serial relay workflow for larger tasks that start with planning and confirmation");

    private final String id;
    private final String description;

    SessionMode(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public void applyTo(ConversationSession session) {
        session.setWorkflowMode(this);
    }

    public static SessionMode from(ConversationSession session) {
        return session == null ? COMMON : session.workflowMode();
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
