package madacode.core;

import madacode.services.api.ApiStreamSink;

import java.util.Objects;

/**
 * Owns the lifecycle of one assistant message within a model iteration.
 *
 * <p>Exactly one of {@link #commit()} or {@link #abandon()} must be called.
 * If neither is called by the time {@link #close()} runs (try-with-resources
 * safety net), the partial stream is abandoned — never silently committed.
 *
 * <p>Centralising the decision here prevents the family of bugs where an
 * error path forgets to abandon a streamed assistant message and a second
 * assistant message is then appended, producing a transcript with two
 * consecutive assistant turns (which the model API rejects on the next round).
 */
public final class AssistantTurnWriter implements AutoCloseable {

    private final ConversationSession session;
    private final StreamingAssistantHandle stream;
    private boolean closed;

    public static AssistantTurnWriter open(ConversationSession session) {
        return new AssistantTurnWriter(session);
    }

    private AssistantTurnWriter(ConversationSession session) {
        this.session = Objects.requireNonNull(session, "session");
        this.stream = session.beginAssistantStream();
    }

    /** Stream sink wired to the underlying assistant message. */
    public ApiStreamSink sink() {
        return new ApiStreamSink() {
            @Override public void onTextDelta(String chunk) { stream.appendText(chunk); }
            @Override public void onToolUseBlock(ContentBlock.ToolUseBlock b) { stream.appendBlock(b); }
            @Override public void onThinkingBlock(ContentBlock.ThinkingBlock b) { stream.appendBlock(b); }
            @Override public void onMessageStart(String model, TokenUsage u) {}
            @Override public void onMessageStop(StopReason sr, TokenUsage u, long ttft, long total) {
                session.fireMetaEvent(new MetaEvent.TokenReport(u, ttft, total));
            }
        };
    }

    /** Commit the streamed message as the assistant turn output. */
    public void commit() {
        ensureOpen();
        stream.finalizeAndAppend();
        closed = true;
    }

    /** Discard the partial stream. The caller is responsible for adding any
     *  replacement message (error / cancellation) afterwards. */
    public void abandon() {
        ensureOpen();
        stream.abandon();
        closed = true;
    }

    @Override
    public void close() {
        if (!closed) abandon();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("writer already closed");
    }
}
