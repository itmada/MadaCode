package madacode.core.session;

import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.model.MessageRole;
import madacode.core.model.MetaEvent;
import madacode.core.model.TokenUsage;

import java.util.ArrayList;
import java.util.List;

/**
 * Builder for a streaming assistant message.
 * Created via {@link ConversationSession#beginAssistantStream()}.
 */
public final class StreamingAssistantHandle {
    private final ConversationSession session;
    private final int reservedIndex;
    private final StringBuilder textBuf = new StringBuilder();
    private final List<ContentBlock> blocks = new ArrayList<>();
    private boolean finalized;

    StreamingAssistantHandle(ConversationSession session, int index) {
        this.session = session;
        this.reservedIndex = index;
    }

    public synchronized void appendText(String chunk) {
        check();
        textBuf.append(chunk);
        for (SessionListener l : session.listenerList()) {
            try {
                l.onAssistantTextChunk(reservedIndex, chunk);
            } catch (RuntimeException e) {
                logListenerCrash("onAssistantTextChunk", e);
            }
        }
    }

    public synchronized void appendBlock(ContentBlock block) {
        check();
        flushPendingText();
        blocks.add(block);
        for (SessionListener l : session.listenerList()) {
            try {
                l.onAssistantBlockAppended(reservedIndex, block);
            } catch (RuntimeException e) {
                logListenerCrash("onAssistantBlockAppended", e);
            }
        }
    }

    /** Finalize the stream: flushes any pending text, assembles the final
     *  Message, adds it to the session (silently — listeners get
     *  {@code onAssistantStreamFinalized} instead of
     *  {@code onMessageAppended}). */
    public synchronized Message finalizeAndAppend() {
        check();
        flushPendingText();
        finalized = true;
        Message message;
        if (blocks.size() == 1 && blocks.getFirst() instanceof ContentBlock.TextBlock tb) {
            message = Message.assistant(tb.text());
        } else {
            message = Message.assistant(List.copyOf(blocks));
        }
        session.appendStreamedMessage(message);
        for (SessionListener l : session.listenerList()) {
            try {
                l.onAssistantStreamFinalized(reservedIndex);
            } catch (RuntimeException e) {
                logListenerCrash("onAssistantStreamFinalized", e);
            }
        }
        return message;
    }

    /**
     * Discard the in-progress stream without appending it to the session.
     * Use when cancellation occurs so that the caller can add a single clean
     * cancellation message rather than leaving an empty or partial assistant
     * message followed by a second assistant message.
     * Renderer state is still cleaned up via {@code onAssistantStreamFinalized}.
     */
    public synchronized void abandon() {
        if (finalized) return;
        finalized = true;
        session.clearStream();
        for (SessionListener l : session.listenerList()) {
            try {
                l.onAssistantStreamFinalized(reservedIndex);
            } catch (RuntimeException e) {
                logListenerCrash("onAssistantStreamFinalized", e);
            }
        }
    }

    public int reservedIndex() {
        return reservedIndex;
    }

    private void flushPendingText() {
        if (textBuf.isEmpty()) return;
        blocks.add(new ContentBlock.TextBlock(textBuf.toString()));
        textBuf.setLength(0);
    }

    private void check() {
        if (finalized) throw new IllegalStateException("stream already finalized");
    }

    private static void logListenerCrash(String callbackName, RuntimeException error) {
        // TODO(P2-1): Keep listener crash logging on the static facade until
        // session listener fanout can accept injected DiagnosticEvents cleanly.
        madacode.logging.DiagnosticEventLogger.listenerCrashed(callbackName, error);
    }
}
