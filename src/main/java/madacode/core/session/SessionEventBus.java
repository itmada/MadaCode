package madacode.core.session;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.model.MetaEvent;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Thread-safe listener fanout for session and rendering events.
 */
public final class SessionEventBus {

    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(SessionListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(SessionListener listener) {
        listeners.remove(listener);
    }

    public List<SessionListener> listeners() {
        return List.copyOf(listeners);
    }

    public void fireMessageAppended(int index, Message message) {
        forEach("onMessageAppended", listener -> listener.onMessageAppended(index, message));
    }

    public void fireAssistantTextChunk(int index, String chunk) {
        forEach("onAssistantTextChunk", listener -> listener.onAssistantTextChunk(index, chunk));
    }

    public void fireAssistantBlockAppended(int index, ContentBlock block) {
        forEach("onAssistantBlockAppended", listener -> listener.onAssistantBlockAppended(index, block));
    }

    public void fireAssistantStreamFinalized(int index) {
        forEach("onAssistantStreamFinalized", listener -> listener.onAssistantStreamFinalized(index));
    }

    public void fireAssistantStreamReset(int index) {
        forEach("onAssistantStreamReset", listener -> listener.onAssistantStreamReset(index));
    }

    public void fireToolExecutionReached(String toolUseId, String toolName, ObjectNode input) {
        forEach("onToolExecutionReached",
                listener -> listener.onToolExecutionReached(toolUseId, toolName, input));
    }

    public void fireToolExecutionStarted(String toolUseId, String toolName, ObjectNode input) {
        forEach("onToolExecutionStarted",
                listener -> listener.onToolExecutionStarted(toolUseId, toolName, input));
    }

    public void fireToolExecutionCompleted(String toolUseId, boolean success, long durationMs) {
        forEach("onToolExecutionCompleted",
                listener -> listener.onToolExecutionCompleted(toolUseId, success, durationMs));
    }

    public void fireToolResultAvailable(String toolUseId, boolean success, String output) {
        forEach("onToolResultAvailable",
                listener -> listener.onToolResultAvailable(toolUseId, success, output));
    }

    public void fireToolExecutionProgress(String toolUseId, String progressText) {
        forEach("onToolExecutionProgress",
                listener -> listener.onToolExecutionProgress(toolUseId, progressText));
    }

    public void fireToolExecutionActivity(String toolUseId, String activityText) {
        forEach("onToolExecutionActivity",
                listener -> listener.onToolExecutionActivity(toolUseId, activityText));
    }

    public void fireToolExecutionMetric(String toolUseId, String metricText) {
        forEach("onToolExecutionMetric",
                listener -> listener.onToolExecutionMetric(toolUseId, metricText));
    }

    public void fireMetaEvent(MetaEvent meta) {
        forEach("onMetaEvent", listener -> listener.onMetaEvent(meta));
    }

    public void fireTurnEnd() {
        forEach("onTurnEnd", SessionListener::onTurnEnd);
    }

    private void forEach(String callbackName, ListenerCallback callback) {
        for (SessionListener listener : listeners) {
            try {
                callback.invoke(listener);
            } catch (RuntimeException e) {
                logListenerCrash(callbackName, e);
            }
        }
    }

    private static void logListenerCrash(String callbackName, RuntimeException error) {
        // TODO(P2-1): Keep listener crash logging on the static facade until
        // session listener fanout can accept injected DiagnosticEvents cleanly.
        madacode.logging.DiagnosticEventLogger.listenerCrashed(callbackName, error);
    }

    @FunctionalInterface
    private interface ListenerCallback {
        void invoke(SessionListener listener);
    }
}
