package madacode.core.engine;

import madacode.core.model.ContentBlock;
import madacode.core.model.FinishReason;
import madacode.core.model.Message;
import madacode.core.model.MetaEvent;
import madacode.core.model.StopReason;
import madacode.core.model.ToolCall;
import madacode.core.model.ToolResult;
import madacode.core.session.AssistantTurnWriter;
import madacode.core.session.ConversationSession;
import madacode.core.turn.CancellationException;
import madacode.core.turn.CancellationToken;
import madacode.core.turn.Turn;
import madacode.core.turn.TurnResult;
import madacode.core.turn.TurnRunner;

import madacode.cli.UnavailablePromptChannel;
import madacode.cli.UserPromptChannel;

import java.nio.file.Path;
import java.util.Objects;

public final class ToolUseContext {

    private final Path workingDirectory;
    private final ConversationSession session;
    private final int depth;
    private final int maxDepth;
    private final CancellationToken cancellationToken;
    private final UserPromptChannel userPrompts;

    public ToolUseContext(Path workingDirectory, ConversationSession session) {
        this(workingDirectory, session, 0, 1, CancellationToken.never(), UnavailablePromptChannel.INSTANCE);
    }

    public ToolUseContext(Path workingDirectory, ConversationSession session, int depth, int maxDepth) {
        this(workingDirectory, session, depth, maxDepth, CancellationToken.never(), UnavailablePromptChannel.INSTANCE);
    }

    public ToolUseContext(Path workingDirectory,
                          ConversationSession session,
                          int depth,
                          int maxDepth,
                          CancellationToken cancellationToken) {
        this(workingDirectory, session, depth, maxDepth, cancellationToken, UnavailablePromptChannel.INSTANCE);
    }

    public ToolUseContext(Path workingDirectory,
                          ConversationSession session,
                          int depth,
                          int maxDepth,
                          CancellationToken cancellationToken,
                          UserPromptChannel userPrompts) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        this.session = Objects.requireNonNull(session, "session");
        this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
        this.userPrompts = Objects.requireNonNull(userPrompts, "userPrompts");
        if (depth < 0) {
            throw new IllegalArgumentException("depth must be >= 0, was " + depth);
        }
        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must be >= 0, was " + maxDepth);
        }
        this.depth = depth;
        this.maxDepth = maxDepth;
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public ConversationSession session() {
        return session;
    }

    public int depth() {
        return depth;
    }

    public int maxDepth() {
        return maxDepth;
    }

    /**
     * Cooperative cancellation signal for this turn. Sub-agents inherit
     * the parent's token (see {@link #childContext}) so a single Ctrl+C
     * cancels the whole tree, not just the topmost agent.
     */
    public CancellationToken cancellationToken() {
        return cancellationToken;
    }

    /** User interaction channel for tools like ask_user_question. */
    public UserPromptChannel userPrompts() {
        return userPrompts;
    }

    public boolean canSpawnSubAgent() {
        return depth < maxDepth;
    }

    public ToolUseContext childContext(ConversationSession childSession) {
        // Sub-agents must not prompt the main user.
        return new ToolUseContext(
                workingDirectory, childSession, depth + 1, maxDepth,
                cancellationToken, UnavailablePromptChannel.INSTANCE);
    }
}
