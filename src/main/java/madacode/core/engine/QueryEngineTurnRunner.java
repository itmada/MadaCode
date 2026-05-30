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
