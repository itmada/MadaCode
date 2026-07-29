package madacode.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.model.MessageRole;
import madacode.permission.PermissionMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionStorageTest {

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadRoundTripsTranscriptState() {
        Path workingDirectory = tempDir.resolve("workspace");
        Instant createdAt = Instant.parse("2026-06-01T12:00:00Z");
        ConversationSession session = new ConversationSession(
                "roundtrip-session",
                createdAt,
                workingDirectory,
                List.of(
                        Message.system("Session initialized."),
                        Message.user("build the plan"),
                        Message.assistant(List.of(
                                new ContentBlock.TextBlock("I will plan it."),
                                new ContentBlock.ThinkingBlock("private reasoning omitted")))));

        session.addInput("first input");
        session.addInput("second input");
        session.setPlanMode(true);
        session.setPermissionMode(PermissionMode.BYPASS);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DRAFT);
        session.setLongRunningTaskId("task-123");
        session.setLongRunningTaskDirectory(workingDirectory.resolve("tasks/task-123").toString());
        session.setLongRunningTaskTitle("Durable session refactor");
        session.setLongRunningReason("Need durable state");
        session.setLongRunningPlanSummary("A concise plan summary");
        session.setLongRunningWorkerSession(true);
        session.loadDeferredTool("zeta_tool");
        session.loadDeferredTool("alpha_tool");

        SessionStorage storage = new SessionStorage(tempDir.resolve("sessions"));
        storage.save(session);

        ConversationSession restored = storage.load(session.sessionId());

        assertEquals(session.sessionId(), restored.sessionId());
        assertEquals(createdAt, restored.createdAt());
        assertEquals(workingDirectory.toAbsolutePath().normalize(), restored.workingDirectory());
        assertMessagesEqual(session.transcriptMessages(), restored.transcriptMessages());
        assertTrue(restored.currentPlan().isEmpty());
        assertEquals(List.of("first input", "second input"), restored.inputHistory());
        assertEquals(SessionMode.LONG_RUNNING, restored.workflowMode());
        assertFalse(restored.isPlanMode());
        assertEquals(PermissionMode.BYPASS, restored.permissionMode());
        assertEquals(LongRunningStage.DRAFT, restored.longRunningStage());
        assertEquals("task-123", restored.longRunningTaskId());
        assertEquals(workingDirectory.resolve("tasks/task-123").toString(), restored.longRunningTaskDirectory());
        assertEquals("Durable session refactor", restored.longRunningTaskTitle());
        assertEquals("Need durable state", restored.longRunningReason());
        assertEquals("A concise plan summary", restored.longRunningPlanSummary());
        assertTrue(restored.isLongRunningWorkerSession());
        assertEquals(session.loadedDeferredTools(), restored.loadedDeferredTools());
    }

    @Test
    void repeatedSaveKeepsTranscriptAppendOnlyWhenModelContextIsCompacted() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir.resolve("sessions"));
        ConversationSession session = new ConversationSession(
                "append-session",
                Instant.parse("2026-06-01T12:00:00Z"),
                tempDir.resolve("workspace"),
                List.of(Message.system("Session initialized.")));

        for (int i = 0; i < 500; i++) {
            session.addMessage(Message.user("u" + i));
        }
        storage.save(session);
        long initialLines;
        try (var lines = java.nio.file.Files.lines(storage.transcriptPath(session.sessionId()))) {
            initialLines = lines.count();
        }

        session.addMessage(Message.assistant("tail"));
        storage.save(session);
        long appendedLines;
        try (var lines = java.nio.file.Files.lines(storage.transcriptPath(session.sessionId()))) {
            appendedLines = lines.count();
        }
        assertEquals(initialLines + 1, appendedLines);

        session.replaceModelContext(List.of(
                Message.system("Session initialized."),
                Message.user("compacted")));
        storage.save(session);
        long compactedLines;
        try (var lines = java.nio.file.Files.lines(storage.transcriptPath(session.sessionId()))) {
            compactedLines = lines.count();
        }
        assertEquals(appendedLines, compactedLines);

        ConversationSession restored = storage.load(session.sessionId());
        assertEquals(502, restored.transcriptMessages().size());
        assertEquals(2, restored.modelContextMessages().size());
        assertEquals("Session initialized.", restored.modelContextMessages().getFirst().content());
        assertEquals("compacted", restored.modelContextMessages().getLast().content());
    }

    @Test
    void loadAppendsTranscriptTailMissingFromContextSnapshot() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir.resolve("sessions"));
        ConversationSession session = new ConversationSession(
                "tail-recovery",
                Instant.parse("2026-06-01T12:00:00Z"),
                tempDir.resolve("workspace"),
                List.of(Message.system("Session initialized."), Message.user("original")));
        session.replaceModelContext(List.of(
                Message.system("Session initialized."), Message.user("summary")));
        storage.save(session);
        Path statePath = tempDir.resolve("sessions/tail-recovery.state.json");
        String staleState = java.nio.file.Files.readString(statePath);

        session.addMessage(Message.assistant("tail"));
        storage.save(session);
        java.nio.file.Files.writeString(statePath, staleState);

        ConversationSession restored = storage.load(session.sessionId());
        assertEquals(List.of("Session initialized.", "summary", "tail"),
                restored.modelContextMessages().stream().map(Message::content).toList());
        assertEquals(List.of("Session initialized.", "original", "tail"),
                restored.transcriptMessages().stream().map(Message::content).toList());
    }

    @Test
    void invalidContextSnapshotFallsBackToTranscript() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir.resolve("sessions"));
        ConversationSession session = new ConversationSession(
                "invalid-context",
                Instant.parse("2026-06-01T12:00:00Z"),
                tempDir.resolve("workspace"),
                List.of(Message.system("Session initialized."), Message.user("original")));
        session.replaceModelContext(List.of(
                Message.system("Session initialized."), Message.user("summary")));
        storage.save(session);
        Path statePath = tempDir.resolve("sessions/invalid-context.state.json");
        String invalidState = java.nio.file.Files.readString(statePath)
                .replace("\"transcriptMessageCount\" : 2", "\"transcriptMessageCount\" : 999");
        java.nio.file.Files.writeString(statePath, invalidState);

        ConversationSession restored = storage.load(session.sessionId());
        assertEquals(restored.transcriptMessages(), restored.modelContextMessages());
    }

    @Test
    void emptyContextSnapshotWithAnAdvancedCursorFallsBackToTranscript() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir.resolve("sessions"));
        ConversationSession session = new ConversationSession(
                "empty-context",
                Instant.parse("2026-06-01T12:00:00Z"),
                tempDir.resolve("workspace"),
                List.of(Message.system("Session initialized."), Message.user("original")));
        session.replaceModelContext(List.of(
                Message.system("Session initialized."), Message.user("summary")));
        storage.save(session);

        Path statePath = tempDir.resolve("sessions/empty-context.state.json");
        ObjectNode state = (ObjectNode) new ObjectMapper().readTree(java.nio.file.Files.readString(statePath));
        ((ArrayNode) state.path("modelContext").path("messages")).removeAll();
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(statePath.toFile(), state);

        ConversationSession restored = storage.load(session.sessionId());

        assertEquals(restored.transcriptMessages(), restored.modelContextMessages());
    }

    @Test
    void malformedStateFallsBackToTranscriptAndDefaultAuxiliaryState() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir.resolve("sessions"));
        ConversationSession session = new ConversationSession(
                "malformed-state",
                Instant.parse("2026-06-01T12:00:00Z"),
                tempDir.resolve("workspace"),
                List.of(Message.system("Session initialized."), Message.user("original")));
        storage.save(session);
        java.nio.file.Files.writeString(
                tempDir.resolve("sessions/malformed-state.state.json"), "{ broken state");

        ConversationSession restored = storage.load(session.sessionId());

        assertMessagesEqual(session.transcriptMessages(), restored.transcriptMessages());
        assertEquals(restored.transcriptMessages(), restored.modelContextMessages());
        assertEquals(PermissionMode.DEFAULT, restored.permissionMode());
    }

    @Test
    void v10StateWithoutModelContextInitializesContextFromTranscript() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir.resolve("sessions"));
        ConversationSession session = new ConversationSession(
                "v10-state",
                Instant.parse("2026-06-01T12:00:00Z"),
                tempDir.resolve("workspace"),
                List.of(Message.system("Session initialized."), Message.user("original")));
        storage.save(session);

        Path statePath = tempDir.resolve("sessions/v10-state.state.json");
        ObjectNode state = (ObjectNode) new ObjectMapper().readTree(java.nio.file.Files.readString(statePath));
        state.put("schemaVersion", 10);
        state.remove("modelContext");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(statePath.toFile(), state);

        ConversationSession restored = storage.load(session.sessionId());

        assertEquals(restored.transcriptMessages(), restored.modelContextMessages());
    }

    @Test
    void loadingACompactedSnapshotDoesNotClaimTheNextTurnWriterThread() throws Exception {
        String previous = System.getProperty("madacode.session.assertWriterThread");
        System.setProperty("madacode.session.assertWriterThread", "true");
        try {
            SessionStorage storage = new SessionStorage(tempDir.resolve("sessions"));
            ConversationSession session = new ConversationSession(
                    "writer-thread",
                    Instant.parse("2026-06-01T12:00:00Z"),
                    tempDir.resolve("workspace"),
                    List.of(Message.system("Session initialized."), Message.user("original")));
            session.replaceModelContext(List.of(
                    Message.system("Session initialized."), Message.user("summary")));
            storage.save(session);

            ConversationSession restored = storage.load(session.sessionId());
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread turnThread = new Thread(() -> {
                try {
                    restored.addMessage(Message.user("next turn"));
                } catch (Throwable error) {
                    failure.set(error);
                }
            }, "mada-turn-exec");
            turnThread.start();
            turnThread.join();

            assertTrue(failure.get() == null, () -> "unexpected writer-thread failure: " + failure.get());
        } finally {
            if (previous == null) {
                System.clearProperty("madacode.session.assertWriterThread");
            } else {
                System.setProperty("madacode.session.assertWriterThread", previous);
            }
        }
    }

    @Test
    void sessionSummaryCountsTheTranscriptWhenStateIsStale() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir.resolve("sessions"));
        ConversationSession session = new ConversationSession(
                "stale-summary",
                Instant.parse("2026-06-01T12:00:00Z"),
                tempDir.resolve("workspace"),
                List.of(Message.system("Session initialized.")));
        storage.save(session);
        Path statePath = tempDir.resolve("sessions/stale-summary.state.json");
        String staleState = java.nio.file.Files.readString(statePath);

        session.addMessage(Message.user("written before state"));
        storage.save(session);
        java.nio.file.Files.writeString(statePath, staleState);

        assertEquals(2, storage.listSessions().getFirst().messageCount());
    }

    @Test
    void saveBacksUpLegacyJsonInsteadOfDeleting() throws Exception {
        Path sessionDir = tempDir.resolve("sessions");
        java.nio.file.Files.createDirectories(sessionDir);

        String legacyContent = """
                {"sessionId":"legacy-ses","createdAt":"2026-06-01T12:00:00Z","workingDirectory":"/tmp",\
                "messages":[{"role":"USER","contentBlocks":[{"type":"text","text":"hello"}]}]}""";
        Path legacyPath = sessionDir.resolve("legacy-ses.json");
        java.nio.file.Files.writeString(legacyPath, legacyContent);

        SessionStorage storage = new SessionStorage(sessionDir);
        ConversationSession session = storage.load("legacy-ses");
        storage.save(session);

        assertFalse(java.nio.file.Files.exists(legacyPath), ".json should be gone after save");
        Path bakPath = sessionDir.resolve("legacy-ses.json.bak");
        assertTrue(java.nio.file.Files.exists(bakPath), ".json.bak should exist after save");
        assertEquals(legacyContent, java.nio.file.Files.readString(bakPath),
                ".json.bak content must match original .json");
        assertTrue(java.nio.file.Files.exists(sessionDir.resolve("legacy-ses.jsonl")));
        assertTrue(java.nio.file.Files.exists(sessionDir.resolve("legacy-ses.state.json")));
    }

    @Test
    void listSessionsDoesNotProduceEntriesFromBakFiles() throws Exception {
        Path sessionDir = tempDir.resolve("sessions");
        java.nio.file.Files.createDirectories(sessionDir);

        String legacyContent = """
                {"sessionId":"bak-test","createdAt":"2026-06-01T12:00:00Z","workingDirectory":"/tmp",\
                "messages":[{"role":"USER","contentBlocks":[{"type":"text","text":"hello"}]}]}""";
        java.nio.file.Files.writeString(sessionDir.resolve("bak-test.json"), legacyContent);

        SessionStorage storage = new SessionStorage(sessionDir);
        ConversationSession session = storage.load("bak-test");
        storage.save(session);

        var sessions = storage.listSessions();
        long count = sessions.stream().filter(s -> s.sessionId().equals("bak-test")).count();
        assertEquals(1, count, "must not produce duplicate entries from .bak files");
    }

    @Test
    void deleteRemovesAllFilesIncludingBak() throws Exception {
        Path sessionDir = tempDir.resolve("sessions");
        java.nio.file.Files.createDirectories(sessionDir);

        String legacyContent = """
                {"sessionId":"del-test","createdAt":"2026-06-01T12:00:00Z","workingDirectory":"/tmp",\
                "messages":[{"role":"USER","contentBlocks":[{"type":"text","text":"hello"}]}]}""";
        java.nio.file.Files.writeString(sessionDir.resolve("del-test.json"), legacyContent);

        SessionStorage storage = new SessionStorage(sessionDir);
        ConversationSession session = storage.load("del-test");
        storage.save(session);

        assertTrue(java.nio.file.Files.exists(sessionDir.resolve("del-test.json.bak")));

        storage.delete("del-test");

        assertFalse(java.nio.file.Files.exists(sessionDir.resolve("del-test.jsonl")));
        assertFalse(java.nio.file.Files.exists(sessionDir.resolve("del-test.state.json")));
        assertFalse(java.nio.file.Files.exists(sessionDir.resolve("del-test.json")));
        assertFalse(java.nio.file.Files.exists(sessionDir.resolve("del-test.json.bak")));
    }

    private static void assertMessagesEqual(List<Message> expected, List<Message> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).role(), actual.get(i).role());
            assertIterableEquals(expected.get(i).contentBlocks(), actual.get(i).contentBlocks());
        }
        assertEquals(MessageRole.USER, actual.get(1).role());
    }
}
