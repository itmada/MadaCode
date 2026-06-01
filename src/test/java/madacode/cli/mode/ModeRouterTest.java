package madacode.cli.mode;

import madacode.core.model.FinishReason;
import madacode.core.session.ConversationSession;
import madacode.core.session.SessionMode;
import madacode.core.turn.TurnHandle;
import madacode.core.turn.TurnResult;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertSame;

class ModeRouterTest {

    @Test
    void defaultsToCommonWhenWorkflowModeIsUnavailable() {
        ModeHandler common = (line, session) -> ModeExecution.managedTurn(noopHandle());
        ModeHandler longRunning = (line, session) -> ModeExecution.managedTurn(noopHandle());
        ModeRouter router = new ModeRouter(common, longRunning);

        assertSame(common, router.handlerFor(new ConversationSession(Path.of("."))));
    }

    @Test
    void selectsLongRunningHandlerWhenWorkflowModeIsLongRunning() {
        ModeHandler common = (line, session) -> ModeExecution.managedTurn(noopHandle());
        ModeHandler longRunning = (line, session) -> ModeExecution.managedTurn(noopHandle());
        ModeRouter router = new ModeRouter(common, longRunning);
        ConversationSession session = new ConversationSession(Path.of("."));
        session.setWorkflowMode(SessionMode.LONG_RUNNING);

        assertSame(longRunning, router.handlerFor(session));
    }

    private static TurnHandle noopHandle() {
        return new TurnHandle(
                "turn",
                CompletableFuture.completedFuture(new TurnResult("ok", FinishReason.COMPLETED, 1)),
                reason -> { });
    }

}
