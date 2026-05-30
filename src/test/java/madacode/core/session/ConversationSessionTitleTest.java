package madacode.core.session;

import madacode.core.model.*;
import madacode.core.turn.*;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConversationSessionTitleTest {

    @Test
    void titleFromFirstUserText() {
        ConversationSession session = new ConversationSession(
                "s1", java.time.Instant.now(), java.nio.file.Path.of("."),
                List.of(
                        Message.system("Init"),
                        Message.user("fix the login bug"),
                        Message.assistant("OK")));

        assertEquals("fix the login bug", session.title());
    }

    @Test
    void titleSkipsToolResultUserMessages() {
        // When the first USER message contains only tool_result blocks,
        // it should skip to the next USER message with text.
        ConversationSession session = new ConversationSession(
                "s2", java.time.Instant.now(), java.nio.file.Path.of("."),
                List.of(
                        Message.system("Init"),
                        Message.user(List.of(
                                new ContentBlock.ToolResultBlock("toolu_1", "result", true, -1))),
                        Message.assistant("done"),
                        Message.user("help me refactor"),
                        Message.assistant("sure")));

        assertEquals("help me refactor", session.title());
    }

    @Test
    void titleEmptySession() {
        ConversationSession session = new ConversationSession(
                "s3", java.time.Instant.now(), java.nio.file.Path.of("."),
                List.of(Message.system("Init")));

        assertEquals("(empty session)", session.title());
    }

    @Test
    void titleTruncatesLongMessages() {
        String longMessage = "a".repeat(80);
        ConversationSession session = new ConversationSession(
                "s4", java.time.Instant.now(), java.nio.file.Path.of("."),
                List.of(
                        Message.system("Init"),
                        Message.user(longMessage)));

        String title = session.title();
        assertEquals(50, title.length());
        assertTrue(title.endsWith("..."));
    }

    @Test
    void titleStripsWhitespace() {
        ConversationSession session = new ConversationSession(
                "s5", java.time.Instant.now(), java.nio.file.Path.of("."),
                List.of(
                        Message.system("Init"),
                        Message.user("   hello world   ")));

        assertEquals("hello world", session.title());
    }
}
