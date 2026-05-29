package madacode.tool;

import madacode.core.ConversationSession;
import madacode.core.SessionListener;
import madacode.core.ToolExecutor;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProgressEmitterTest {

    @Test
    void noopsWhenToolUseIdMissing() {
        ConversationSession session = new ConversationSession(Path.of("."));
        List<String> progress = captureProgress(session);

        new ProgressEmitter(session, 0).emit("hello");

        assertEquals(List.of(), progress);
    }

    @Test
    void emitsProgressWhenToolUseIdPresent() {
        ConversationSession session = new ConversationSession(Path.of("."));
        List<String> progress = captureProgress(session);

        ToolExecutor.CURRENT_TOOL_USE_ID.set("toolu_test");
        try {
            new ProgressEmitter(session, 0).emit("hello");
        } finally {
            ToolExecutor.CURRENT_TOOL_USE_ID.remove();
        }

        assertEquals(List.of("hello"), progress);
    }

    @Test
    void throttlesEmitsWithinInterval() {
        ConversationSession session = new ConversationSession(Path.of("."));
        List<String> progress = captureProgress(session);

        ToolExecutor.CURRENT_TOOL_USE_ID.set("toolu_test");
        try {
            ProgressEmitter emitter = new ProgressEmitter(session, 60_000);
            emitter.emitThrottled("one");
            emitter.emitThrottled("two");
        } finally {
            ToolExecutor.CURRENT_TOOL_USE_ID.remove();
        }

        assertEquals(List.of("one"), progress);
    }

    private static List<String> captureProgress(ConversationSession session) {
        List<String> progress = new ArrayList<>();
        session.addListener(new SessionListener() {
            @Override
            public void onToolExecutionProgress(String toolUseId, String progressText) {
                progress.add(progressText);
            }
        });
        return progress;
    }
}
