package madacode.tool;

import madacode.core.session.ConversationSession;
import madacode.core.engine.ToolExecutor;

public final class ProgressEmitter {

    private final ConversationSession session;
    private final long minIntervalMs;
    private volatile long lastEmitAtMs;

    public ProgressEmitter(ConversationSession session, long minIntervalMs) {
        this.session = session;
        this.minIntervalMs = Math.max(0L, minIntervalMs);
    }

    public void emit(String text) {
        fire(text, true, false);
    }

    public void emitThrottled(String text) {
        fire(text, false, false);
    }

    public void emitMetric(String text) {
        fire(text, true, true);
    }

    public void emitMetricThrottled(String text) {
        fire(text, false, true);
    }

    private void fire(String text, boolean force, boolean isMetric) {
        try {
            String toolUseId = ToolExecutor.CURRENT_TOOL_USE_ID.get();
            if (toolUseId == null || toolUseId.isBlank() || session == null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (!force && minIntervalMs > 0 && now - lastEmitAtMs < minIntervalMs) {
                return;
            }
            if (isMetric) {
                session.fireToolExecutionMetric(toolUseId, text);
            } else {
                session.fireToolExecutionProgress(toolUseId, text);
            }
            lastEmitAtMs = now;
        } catch (RuntimeException ignored) {
            // Progress is best-effort only and must never break the tool.
        }
    }
}
