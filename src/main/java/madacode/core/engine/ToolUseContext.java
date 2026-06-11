package madacode.core.engine;

import madacode.core.session.ConversationSession;
import madacode.core.turn.CancellationToken;
import madacode.cli.UnavailablePromptChannel;
import madacode.cli.UserPromptChannel;
import madacode.tool.VisibleTools;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

public final class ToolUseContext {

    private final Path workingDirectory;
    private final ConversationSession session;
    private final int depth;
    private final int maxDepth;
    private final CancellationToken cancellationToken;
    private final UserPromptChannel userPrompts;
    private final Set<String> exposedToolNames;

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
        this(workingDirectory, session, depth, maxDepth, cancellationToken, userPrompts, null);
    }

    private ToolUseContext(Path workingDirectory,
                           ConversationSession session,
                           int depth,
                           int maxDepth,
                           CancellationToken cancellationToken,
                           UserPromptChannel userPrompts,
                           Set<String> exposedToolNames) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        this.session = Objects.requireNonNull(session, "session");
        this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
        this.userPrompts = Objects.requireNonNull(userPrompts, "userPrompts");
        this.exposedToolNames = exposedToolNames == null ? null : Set.copyOf(exposedToolNames);
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

    /**
     * Returns a copy of this context bound to the tool declarations that were
     * actually sent with the current model request. Tool execution uses this
     * snapshot as a hard boundary, so tools loaded or hidden after the request
     * cannot be smuggled into the same tool batch.
     */
    public ToolUseContext withExposedTools(VisibleTools tools) {
        Set<String> names = tools == null
                ? Set.of()
                : Set.copyOf(tools.names());
        return new ToolUseContext(
                workingDirectory, session, depth, maxDepth,
                cancellationToken, userPrompts, names);
    }

    public boolean hasExposedToolSnapshot() {
        return exposedToolNames != null;
    }

    public boolean wasToolExposed(String canonicalToolName) {
        if (exposedToolNames == null) {
            return true;
        }
        return exposedToolNames.contains(canonicalToolName);
    }

    public ToolUseContext childContext(ConversationSession childSession) {
        // Sub-agents must not prompt the main user.
        return new ToolUseContext(
                workingDirectory, childSession, depth + 1, maxDepth,
                cancellationToken, UnavailablePromptChannel.INSTANCE);
    }
}
