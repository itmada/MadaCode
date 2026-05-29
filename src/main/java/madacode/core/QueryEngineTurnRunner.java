package madacode.core;

import madacode.cli.UserPromptChannel;

import java.util.Objects;

/**
 * Production {@link TurnRunner} that delegates to {@link QueryEngine#runTurn}.
 */
public final class QueryEngineTurnRunner implements TurnRunner {

    private final QueryEngine queryEngine;
    private final UserPromptChannel prompts;

    public QueryEngineTurnRunner(QueryEngine queryEngine) {
        this(queryEngine, madacode.cli.HeadlessPromptChannel.INSTANCE);
    }

    public QueryEngineTurnRunner(QueryEngine queryEngine, UserPromptChannel prompts) {
        this.queryEngine = Objects.requireNonNull(queryEngine, "queryEngine");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
    }

    @Override
    public TurnResult run(Turn turn, ConversationSession session, CancellationToken token)
            throws Exception {
        ToolUseContext ctx = new ToolUseContext(
                session.workingDirectory(), session, 0, 1, token, prompts);
        return queryEngine.runTurn(session, turn.userInput(), ctx);
    }
}
