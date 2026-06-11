package madacode.core.session;

import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.model.MessageRole;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationSessionTest {

    @Test
    void addMessageRejectsConsecutiveSameRoleMessagesExceptSystem() {
        ConversationSession session = new ConversationSession();

        session.addMessage(Message.system("another system marker"));
        session.addMessage(Message.user("first user input"));

        IllegalStateException userException = assertThrows(
                IllegalStateException.class,
                () -> session.addMessage(Message.user("second user input")));
        assertTrue(userException.getMessage().contains("Consecutive same-role messages"));

        session.addMessage(Message.assistant("assistant reply"));
        IllegalStateException assistantException = assertThrows(
                IllegalStateException.class,
                () -> session.addMessage(Message.assistant("second assistant reply")));
        assertTrue(assistantException.getMessage().contains("Consecutive same-role messages"));
    }

    @Test
    void addMessageRejectsMessagesWhileAssistantStreamIsOpen() {
        ConversationSession session = new ConversationSession();
        StreamingAssistantHandle handle = session.beginAssistantStream();

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> session.addMessage(Message.user("new input")));

        assertTrue(exception.getMessage().contains("assistant stream is open"));
        handle.abandon();
        session.addMessage(Message.user("new input"));
        assertEquals("new input", textOf(session.messages().getLast()));
    }

    @Test
    void addControllerEventInsertsSeparatorEventAndBarrierAfterUserTail() {
        ConversationSession session = new ConversationSession();
        session.addMessage(Message.user("real user prompt"));

        session.addControllerEvent(
                "tool run",
                linkedMap(
                        "tool", "read_file",
                        "status", "completed\nwith output"));

        List<Message> messages = session.messages();
        assertEquals(5, messages.size());
        assertMessage(messages.get(1), MessageRole.USER, "real user prompt");
        assertMessage(messages.get(2), MessageRole.SYSTEM, "[controller-event separator]");
        assertEquals(MessageRole.USER, messages.get(3).role());
        assertTrue(textOf(messages.get(3)).startsWith("[controller-event][tool_run]\ntime: "));
        assertTrue(textOf(messages.get(3)).contains("\ntool: read_file"));
        assertTrue(textOf(messages.get(3)).contains("\nstatus: completed with output"));
        assertMessage(messages.get(4), MessageRole.SYSTEM, "[controller-event barrier]");
    }

    @Test
    void queuedControllerEventsFlushInOrderAndClearPendingQueue() {
        ConversationSession session = new ConversationSession();
        session.addMessage(Message.user("real user prompt"));
        session.enqueueControllerEvent("first", linkedMap("value", "one"));
        session.enqueueControllerEvent("second", linkedMap("value", "two"));

        assertEquals(2, session.messages().size());

        session.flushPendingControllerEvents();

        List<Message> messages = session.messages();
        assertEquals(7, messages.size());
        assertMessage(messages.get(2), MessageRole.SYSTEM, "[controller-event separator]");
        assertEquals(MessageRole.USER, messages.get(3).role());
        assertTrue(textOf(messages.get(3)).startsWith("[controller-event][first]\ntime: "));
        assertTrue(textOf(messages.get(3)).contains("\nvalue: one"));
        assertMessage(messages.get(4), MessageRole.SYSTEM, "[controller-event barrier]");
        assertEquals(MessageRole.USER, messages.get(5).role());
        assertTrue(textOf(messages.get(5)).startsWith("[controller-event][second]\ntime: "));
        assertTrue(textOf(messages.get(5)).contains("\nvalue: two"));
        assertMessage(messages.get(6), MessageRole.SYSTEM, "[controller-event barrier]");

        session.flushPendingControllerEvents();
        assertEquals(7, session.messages().size());
    }

    @Test
    void titleSkipsControllerEventMessagesAndUsesFirstRealUserInput() {
        ConversationSession session = new ConversationSession();
        session.addControllerEvent("runtime", Map.of("status", "ready"));
        session.addMessage(Message.user("Describe the session"));

        assertEquals("Describe the session", session.title());
    }

    private static LinkedHashMap<String, String> linkedMap(String firstKey, String firstValue, String secondKey,
            String secondValue) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put(firstKey, firstValue);
        values.put(secondKey, secondValue);
        return values;
    }

    private static LinkedHashMap<String, String> linkedMap(String key, String value) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put(key, value);
        return values;
    }

    private static void assertMessage(Message message, MessageRole role, String text) {
        assertEquals(role, message.role());
        assertEquals(text, textOf(message));
    }

    private static String textOf(Message message) {
        ContentBlock block = message.contentBlocks().getFirst();
        return ((ContentBlock.TextBlock) block).text();
    }
}
