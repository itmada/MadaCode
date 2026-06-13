package madacode.tui.widget;

import madacode.core.session.SessionMode;
import madacode.core.session.ConversationSession;
import madacode.permission.PermissionMode;

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
    private SessionMode workflowMode = SessionMode.COMMON;
    private PermissionMode permissionMode = PermissionMode.DEFAULT;
    private boolean planMode;

    public SessionContext() {}

    public synchronized void setCwd(Path cwd) { this.cwd = cwd; }
    public synchronized Path cwd() { return cwd; }
    public synchronized void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public synchronized void setModel(String model) { this.model = model; }
    public synchronized String model() { return model; }
    public synchronized void setTokenLimit(int tokenLimit) { this.tokenLimit = Math.max(0, tokenLimit); }
    public synchronized void setWorkflowMode(SessionMode workflowMode) {
        this.workflowMode = workflowMode == null ? SessionMode.COMMON : workflowMode;
    }
    public synchronized SessionMode workflowMode() { return workflowMode; }
    public synchronized void setPermissionMode(PermissionMode permissionMode) {
        this.permissionMode = permissionMode == null ? PermissionMode.DEFAULT : permissionMode;
    }
    public synchronized PermissionMode permissionMode() { return permissionMode; }
    public synchronized void setPlanMode(boolean planMode) { this.planMode = planMode; }
    public synchronized boolean planMode() { return planMode; }
    public synchronized void setMode(SessionMode mode) { setWorkflowMode(mode); }
    public synchronized SessionMode mode() { return workflowMode(); }
    public synchronized int tokens() { return tokens; }
    public synchronized void setTokens(int used) { setTokens(used, tokenLimit); }
    public synchronized void setTokens(int used, int max) { this.tokenLimit = Math.max(0, max); this.tokens = used; }

    /** Percent of the model context window in use; -1 when the limit is unknown. */
    public synchronized int contextPercent() {
        if (tokenLimit <= 0) return -1;
        return Math.min(100, (int) Math.round(tokens * 100.0 / tokenLimit));
    }

    public synchronized void syncFrom(ConversationSession session) {
        if (session == null) {
            workflowMode = SessionMode.COMMON;
            permissionMode = PermissionMode.DEFAULT;
            planMode = false;
            return;
        }
        workflowMode = session.workflowMode();
        permissionMode = session.permissionMode();
        planMode = session.isPlanMode();
    }

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
