package madacode.core.session;

import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.model.MessageKind;
import madacode.core.model.MessageRole;
import madacode.governance.ApprovalPosture;
import madacode.permission.PermissionMode;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationSessionTest {

    @Test
    void addMessageAllowsConsecutiveSameRoleMessages() {
        ConversationSession session = new ConversationSession();

        session.addMessage(Message.system("another system marker"));
        session.addMessage(Message.user("first user input"));
        session.addMessage(Message.user("second user input"));

        session.addMessage(Message.assistant("assistant reply"));
        session.addMessage(Message.assistant("second assistant reply"));

        List<Message> messages = session.transcriptMessages();
        assertEquals("first user input", textOf(messages.get(2)));
        assertEquals("second user input", textOf(messages.get(3)));
        assertEquals("assistant reply", textOf(messages.get(4)));
        assertEquals("second assistant reply", textOf(messages.get(5)));
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
        assertEquals("new input", textOf(session.transcriptMessages().getLast()));
    }

    @Test
    void addControllerEventAppendsTypedControllerEventWithoutSyntheticMarkers() {
        ConversationSession session = new ConversationSession();
        session.addMessage(Message.user("real user prompt"));

        session.addControllerEvent(
                "tool run",
                linkedMap(
                        "tool", "read_file",
                        "status", "completed\nwith output"));

        List<Message> messages = session.transcriptMessages();
        assertEquals(3, messages.size());
        assertMessage(messages.get(1), MessageRole.USER, "real user prompt");
        assertEquals(MessageRole.USER, messages.get(2).role());
        assertEquals(MessageKind.CONTROLLER_EVENT, messages.get(2).kind());
        assertTrue(textOf(messages.get(2)).startsWith("[controller-event][tool_run]\ntime: "));
        assertTrue(textOf(messages.get(2)).contains("\ntool: read_file"));
        assertTrue(textOf(messages.get(2)).contains("\nstatus: completed with output"));
    }

    @Test
    void queuedControllerEventsFlushInOrderAndClearPendingQueue() {
        ConversationSession session = new ConversationSession();
        session.addMessage(Message.user("real user prompt"));
        session.enqueueControllerEvent("first", linkedMap("value", "one"));
        session.enqueueControllerEvent("second", linkedMap("value", "two"));

        assertEquals(2, session.transcriptMessages().size());

        session.flushPendingControllerEvents();

        List<Message> messages = session.transcriptMessages();
        assertEquals(4, messages.size());
        assertEquals(MessageKind.CONTROLLER_EVENT, messages.get(2).kind());
        assertTrue(textOf(messages.get(2)).startsWith("[controller-event][first]\ntime: "));
        assertTrue(textOf(messages.get(2)).contains("\nvalue: one"));
        assertEquals(MessageKind.CONTROLLER_EVENT, messages.get(3).kind());
        assertTrue(textOf(messages.get(3)).startsWith("[controller-event][second]\ntime: "));
        assertTrue(textOf(messages.get(3)).contains("\nvalue: two"));

        session.flushPendingControllerEvents();
        assertEquals(4, session.transcriptMessages().size());
    }

    @Test
    void titleSkipsControllerEventMessagesAndUsesFirstRealUserInput() {
        ConversationSession session = new ConversationSession();
        session.addControllerEvent("runtime", Map.of("status", "ready"));
        session.addMessage(Message.user("Describe the session"));

        assertEquals("Describe the session", session.title());
    }

    @Test
    void resetDiscardsBufferedStreamAndFiresResetEvent() {
        ConversationSession session = new ConversationSession();
        int reservedIndex = session.transcriptMessages().size();
        List<String> events = new ArrayList<>();
        session.eventBus().addListener(new SessionListener() {
            @Override public void onAssistantTextChunk(int index, String chunk) { events.add("chunk:" + chunk); }
            @Override public void onAssistantStreamReset(int index) { events.add("reset:" + index); }
        });

        StreamingAssistantHandle handle = session.beginAssistantStream();
        handle.appendText("partial-from-failed-attempt");
        handle.reset();
        handle.appendText("clean-retry-output");
        Message committed = handle.finalizeAndAppend();

        assertEquals("clean-retry-output", textOf(committed));
        assertEquals(
                List.of("chunk:partial-from-failed-attempt", "reset:" + reservedIndex, "chunk:clean-retry-output"),
                events);
        assertEquals(session.transcriptMessages(), session.modelContextMessages());
    }

    @Test
    void replacingModelContextPreservesTranscriptAndAppendsToBothViews() {
        ConversationSession session = new ConversationSession();
        session.addMessage(Message.user("original"));

        session.replaceModelContext(List.of(
                Message.system("Session initialized."),
                Message.user("summary")));
        session.addMessage(Message.assistant("new reply"));

        assertEquals(List.of("Session initialized.", "original", "new reply"),
                session.transcriptMessages().stream().map(Message::content).toList());
        assertEquals(List.of("Session initialized.", "summary", "new reply"),
                session.modelContextMessages().stream().map(Message::content).toList());
        assertTrue(session.hasModelContextSnapshot());
    }

    @Test
    void listenerIndicesAlwaysFollowTheTranscriptAfterContextCompaction() {
        ConversationSession session = new ConversationSession();
        List<Integer> appended = new ArrayList<>();
        List<Integer> finalized = new ArrayList<>();
        session.eventBus().addListener(new SessionListener() {
            @Override public void onMessageAppended(int index, Message message) { appended.add(index); }
            @Override public void onAssistantStreamFinalized(int index) { finalized.add(index); }
        });

        session.addMessage(Message.user("archived prompt"));
        session.replaceModelContext(List.of(Message.system("Session initialized."), Message.user("summary")));
        session.addMessage(Message.user("next prompt"));
        StreamingAssistantHandle stream = session.beginAssistantStream();
        stream.appendText("streamed reply");
        stream.finalizeAndAppend();

        assertEquals(List.of(1, 2), appended);
        assertEquals(List.of(3), finalized);
        assertEquals(4, session.transcriptMessages().size());
        assertEquals(4, session.modelContextMessages().size());
    }

    @Test
    void replayUsesTheCompleteTranscriptAfterContextCompaction() {
        ConversationSession session = new ConversationSession();
        session.addMessage(Message.user("archived prompt"));
        session.replaceModelContext(List.of(Message.system("Session initialized."), Message.user("summary")));
        List<String> replayed = new ArrayList<>();

        session.replay(new SessionListener() {
            @Override public void onMessageAppended(int index, Message message) {
                replayed.add(message.content());
            }
        });

        assertEquals(List.of("Session initialized.", "archived prompt"), replayed);
    }

    @Test
    void capabilityProfileReflectsPermissionMode() {
        ConversationSession session = new ConversationSession();
        session.setPermissionMode(PermissionMode.BYPASS);

        assertEquals(ApprovalPosture.bypassInteractive(), session.capabilityProfile().approvalPosture());
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
