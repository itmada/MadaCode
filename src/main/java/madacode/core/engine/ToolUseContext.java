package madacode.core.engine;

import madacode.core.session.ConversationSession;
import madacode.core.turn.CancellationToken;
import madacode.cli.UnavailablePromptChannel;
import madacode.cli.UserPromptChannel;
import madacode.tool.VisibleTools;
import madacode.tool.access.ToolCapabilityProfile;
import madacode.tool.access.ToolAccessScope;

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
    private final ToolAccessScope toolAccessScope;

    /**
     * Root context for a turn: depth 0, single-level spawn budget, no cancellation,
     * no user prompts, and a session-derived access scope. Every other shape is built
     * from here with the {@code withX} copy methods, mirroring {@link #withExposedTools}
     * and {@link #childContext}.
     */
    public ToolUseContext(Path workingDirectory, ConversationSession session) {
        this(workingDirectory, session, 0, 1, CancellationToken.never(),
                UnavailablePromptChannel.INSTANCE, ToolAccessScope.forSession(session));
    }

    private ToolUseContext(Path workingDirectory,
                           ConversationSession session,
                           int depth,
                           int maxDepth,
                           CancellationToken cancellationToken,
                           UserPromptChannel userPrompts,
                           ToolAccessScope toolAccessScope) {
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        this.session = Objects.requireNonNull(session, "session");
        this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
        this.userPrompts = Objects.requireNonNull(userPrompts, "userPrompts");
        this.toolAccessScope = toolAccessScope == null
                ? ToolAccessScope.forSession(session)
                : toolAccessScope;
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

    public ToolAccessScope toolAccessScope() {
        return toolAccessScope;
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
    /** Copy bound to a cooperative cancellation signal for this turn. */
    public ToolUseContext withCancellationToken(CancellationToken cancellationToken) {
        return new ToolUseContext(
                workingDirectory, session, depth, maxDepth,
                cancellationToken, userPrompts, toolAccessScope);
    }

    /** Copy bound to a user interaction channel (e.g. ask_user_question). */
    public ToolUseContext withUserPrompts(UserPromptChannel userPrompts) {
        return new ToolUseContext(
                workingDirectory, session, depth, maxDepth,
                cancellationToken, userPrompts, toolAccessScope);
    }

    public ToolUseContext withExposedTools(VisibleTools tools) {
        Set<String> names = tools == null
                ? Set.of()
                : Set.copyOf(tools.names());
        return new ToolUseContext(
                workingDirectory, session, depth, maxDepth,
                cancellationToken, userPrompts, toolAccessScope.withRequestExposedToolNames(names));
    }

    public ToolUseContext childContext(ConversationSession childSession) {
        return childContext(childSession, ToolCapabilityProfile.unrestricted());
    }

    public ToolUseContext childContext(ConversationSession childSession, ToolCapabilityProfile childProfile) {
        // Sub-agents must not prompt the main user.
        return new ToolUseContext(
                workingDirectory, childSession, depth + 1, maxDepth,
                cancellationToken, UnavailablePromptChannel.INSTANCE,
                ToolAccessScope.forSubAgent(
                        childSession,
                        childProfile,
                        toolAccessScope.loadedToolNamesSnapshot()));
    }
}
