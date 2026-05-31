package madacode.tui.widget;

import madacode.core.session.SessionMode;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * REPL session context: cwd, session id, model, mode, token counts.
 * Pure data holder — the REPL reads these fields to build an inline prompt prefix.
 */
public final class SessionContext {

    private Path cwd;
    private String sessionId;
    private String model;
    private int tokens;
    private int tokenLimit;
    private SessionMode mode = SessionMode.STRICT;

    public SessionContext() {}

    public synchronized void setCwd(Path cwd) { this.cwd = cwd; }
    public synchronized void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public synchronized void setModel(String model) { this.model = model; }
    public synchronized String model() { return model; }
    public synchronized void setTokenLimit(int tokenLimit) { this.tokenLimit = Math.max(0, tokenLimit); }
    public synchronized void setMode(SessionMode mode) { this.mode = mode == null ? SessionMode.STRICT : mode; }
    public synchronized SessionMode mode() { return mode; }
    public synchronized void setTokens(int used) { setTokens(used, tokenLimit); }
    public synchronized void setTokens(int used, int max) { this.tokenLimit = Math.max(0, max); this.tokens = used; }

    public synchronized void batch(Runnable mutations) {
        mutations.run();
    }

    public synchronized String shortCwd() {
        if (cwd == null) return "";
        String home = System.getProperty("user.home");
        if (home == null) return cwd.toString();
        Path homePath = Paths.get(home);
        if (cwd.startsWith(homePath)) {
            Path rel = homePath.relativize(cwd);
            return rel.toString().isEmpty() ? "~" : "~/" + rel;
        }
        return cwd.toString();
    }

    public synchronized String shortSessionId() {
        return sessionId != null && sessionId.length() > 8
                ? sessionId.substring(0, 8) : sessionId;
    }
}
