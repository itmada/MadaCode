package madacode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.model.ContentBlock;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.model.Message;
import madacode.core.session.SessionMode;
import madacode.core.session.SessionStorage;
import madacode.core.session.SessionStorageException;
import madacode.permission.PermissionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SessionStorageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void saveAndLoadRoundTripsStructuredTranscript() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir);
        ConversationSession session = new ConversationSession(
                "session-123",
                Instant.parse("2026-04-22T08:30:00Z"),
                tempDir.resolve("workspace"),
                List.of(
                        Message.system("Session initialized."),
                        Message.user("inspect plan"),
                        Message.assistant(List.of(
                                new ContentBlock.TextBlock("I will inspect the plan."),
                                new ContentBlock.ToolUseBlock("toolu_1", "glob", toolInput()))),
                        Message.user(List.of(
                                new ContentBlock.ToolResultBlock("toolu_1", "PLAN.md", true, -1)))));
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setPlanMode(true);
        session.setPermissionMode(PermissionMode.BYPASS);
        session.setLongRunningStage(LongRunningStage.DRAFT);
        session.setLongRunningReason("discuss refactor scope");
        session.setExecutionStarted(false);

        storage.save(session);
        ConversationSession restored = storage.load(session.sessionId());

        var json = mapper.readTree(storage.transcriptPath(session.sessionId()).toFile());
        assertEquals(7, json
                .path("schemaVersion")
                .asInt());
        assertEquals("long-running", json.path("workflowMode").asText());
        assertEquals("all-pass", json.path("permissionMode").asText());
        assertEquals(session.sessionId(), restored.sessionId());
        assertEquals(session.createdAt(), restored.createdAt());
        assertEquals(session.workingDirectory(), restored.workingDirectory());
        assertEquals(session.workflowMode(), restored.workflowMode());
        assertEquals(session.isPlanMode(), restored.isPlanMode());
        assertNull(restored.longRunningTaskId());
        assertNull(restored.longRunningTaskDirectory());
        assertEquals(session.permissionMode(), restored.permissionMode());
        assertEquals(session.longRunningStage(), restored.longRunningStage());
        assertEquals("discuss refactor scope", restored.longRunningReason());
        assertEquals(false, restored.executionStarted());
        assertEquals(session.messages().size(), restored.messages().size());

        for (int i = 0; i < session.messages().size(); i++) {
            Message expected = session.messages().get(i);
            Message actual = restored.messages().get(i);
            assertEquals(expected.role(), actual.role());
            assertIterableEquals(expected.contentBlocks(), actual.contentBlocks());
        }
    }

    @Test
    void defaultStorageUsesMadaSessionsDirectory() {
        String previousHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempDir.toString());

            Path transcriptPath = SessionStorage.defaultStorage().transcriptPath("session-abc");

            assertEquals(tempDir.resolve(".mada/sessions/session-abc.json"), transcriptPath);
        } finally {
            if (previousHome == null) {
                System.clearProperty("user.home");
            } else {
                System.setProperty("user.home", previousHome);
            }
        }
    }

    @Test
    void loadTreatsMissingSchemaVersionAsVersionOne() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir);
        String transcript = """
                {
                  "sessionId": "legacy-session",
                  "createdAt": "2026-04-22T08:30:00Z",
                  "workingDirectory": "%s",
                  "messages": [
                    {
                      "role": "SYSTEM",
                      "contentBlocks": [
                        {
                          "type": "text",
                          "text": "Session initialized."
                        }
                      ]
                    }
                  ]
                }
                """.formatted(tempDir.resolve("workspace").toString().replace("\\", "\\\\"));
        java.nio.file.Files.createDirectories(storage.transcriptPath("legacy-session").getParent());
        java.nio.file.Files.writeString(storage.transcriptPath("legacy-session"), transcript);

        ConversationSession restored = storage.load("legacy-session");

        assertEquals("legacy-session", restored.sessionId());
        assertEquals(1, restored.messages().size());
        assertEquals(false, restored.isPlanMode());
        assertEquals(SessionMode.COMMON, restored.workflowMode());
        assertEquals(PermissionMode.DEFAULT, restored.permissionMode());
        assertNull(restored.longRunningStage());
        assertNull(restored.longRunningTaskId());
    }

    @Test
    void saveAndLoadRoundTripsLongRunningWorkflowState() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir);
        ConversationSession session = new ConversationSession(
                "long-running-session",
                Instant.parse("2026-05-21T09:15:00Z"),
                tempDir.resolve("workspace"),
                List.of(Message.system("Session initialized.")));
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DRAFT);
        session.setLongRunningTaskId("task-42");
        session.setLongRunningTaskDirectory(tempDir.resolve("workspace/tasks/task-42").toString());
        session.setLongRunningTaskTitle("Implement long-running workflow");
        session.setLongRunningPlanSummary("Finalize a durable serial execution plan.");
        session.setLongRunningReason("user entered long-running mode");
        session.setExecutionStarted(false);

        storage.save(session);
        ConversationSession restored = storage.load(session.sessionId());

        assertEquals(SessionMode.LONG_RUNNING, restored.workflowMode());
        assertEquals(LongRunningStage.DRAFT, restored.longRunningStage());
        assertEquals("task-42", restored.longRunningTaskId());
        assertEquals(tempDir.resolve("workspace/tasks/task-42").toString(), restored.longRunningTaskDirectory());
        assertEquals("Implement long-running workflow", restored.longRunningTaskTitle());
        assertEquals("Finalize a durable serial execution plan.", restored.longRunningPlanSummary());
        assertEquals("user entered long-running mode", restored.longRunningReason());
        assertEquals(false, restored.executionStarted());
    }

    @Test
    void saveAndLoadPreservesLongRunningWorkerMarkerAndWorkerReport() {
        SessionStorage storage = new SessionStorage(tempDir);
        ConversationSession session = new ConversationSession(
                "worker-session",
                Instant.parse("2026-05-21T10:15:00Z"),
                tempDir.resolve("workspace"),
                List.of(Message.system("Session initialized.")));
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);
        session.setLongRunningTaskId("task-worker");
        session.setExecutionStarted(true);
        session.setLongRunningWorkerSession(true);
        session.recordWorkerReport(new madacode.longrunning.WorkerReport(
                "task-worker",
                "worker-session",
                madacode.longrunning.WorkerReport.Status.PROGRESS_MADE,
                "updated files",
                null,
                null,
                List.of("src/App.java"),
                List.of("mvn test"),
                "continue"));

        storage.save(session);
        ConversationSession restored = storage.load(session.sessionId());

        assertTrue(restored.isLongRunningWorkerSession());
        assertEquals(LongRunningStage.RUNNING, restored.longRunningStage());
        assertEquals("task-worker", restored.longRunningTaskId());
        assertEquals(true, restored.executionStarted());
        assertTrue(restored.lastWorkerReport().isPresent());
        assertEquals("updated files", restored.lastWorkerReport().orElseThrow().summary());
    }

    @Test
    void loadDefaultsToCommonWhenWorkflowFieldsAreMissing() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir);
        String transcript = """
                {
                  "schemaVersion": 5,
                  "sessionId": "workflow-legacy",
                  "createdAt": "2026-05-22T08:30:00Z",
                  "workingDirectory": "%s",
                  "planMode": true,
                  "messages": [
                    {
                      "role": "SYSTEM",
                      "contentBlocks": [
                        {
                          "type": "text",
                          "text": "Session initialized."
                        }
                      ]
                    }
                  ],
                  "tasks": [],
                  "todos": [],
                  "history": []
                }
                """.formatted(tempDir.resolve("workspace").toString().replace("\\", "\\\\"));
        Files.createDirectories(storage.transcriptPath("workflow-legacy").getParent());
        Files.writeString(storage.transcriptPath("workflow-legacy"), transcript);

        ConversationSession restored = storage.load("workflow-legacy");

        assertEquals(SessionMode.COMMON, restored.workflowMode());
        assertNull(restored.longRunningStage());
        assertNull(restored.longRunningTaskId());
        assertNull(restored.longRunningTaskDirectory());
        assertTrue(restored.isPlanMode());
    }

    @Test
    void loadRejectsInvalidWorkflowModeEnum() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir);
        String transcript = """
                {
                  "schemaVersion": 5,
                  "sessionId": "bad-workflow-mode",
                  "createdAt": "2026-05-22T08:30:00Z",
                  "workingDirectory": "%s",
                  "workflowMode": "workflow-x",
                  "messages": [
                    {
                      "role": "SYSTEM",
                      "contentBlocks": [
                        {
                          "type": "text",
                          "text": "Session initialized."
                        }
                      ]
                    }
                  ],
                  "tasks": [],
                  "todos": [],
                  "history": []
                }
                """.formatted(tempDir.resolve("workspace").toString().replace("\\", "\\\\"));
        Files.createDirectories(storage.transcriptPath("bad-workflow-mode").getParent());
        Files.writeString(storage.transcriptPath("bad-workflow-mode"), transcript);

        SessionStorageException exception = assertThrows(
                SessionStorageException.class,
                () -> storage.load("bad-workflow-mode"));

        assertTrue(exception.getMessage().contains("Unsupported workflowMode"));
    }

    @Test
    void loadRejectsInvalidLongRunningStageEnum() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir);
        String transcript = """
                {
                  "schemaVersion": 5,
                  "sessionId": "bad-long-running-stage",
                  "createdAt": "2026-05-22T08:30:00Z",
                  "workingDirectory": "%s",
                  "workflowMode": "long-running",
                  "longRunningStage": "PAUSED",
                  "messages": [
                    {
                      "role": "SYSTEM",
                      "contentBlocks": [
                        {
                          "type": "text",
                          "text": "Session initialized."
                        }
                      ]
                    }
                  ],
                  "tasks": [],
                  "todos": [],
                  "history": []
                }
                """.formatted(tempDir.resolve("workspace").toString().replace("\\", "\\\\"));
        Files.createDirectories(storage.transcriptPath("bad-long-running-stage").getParent());
        Files.writeString(storage.transcriptPath("bad-long-running-stage"), transcript);

        SessionStorageException exception = assertThrows(
                SessionStorageException.class,
                () -> storage.load("bad-long-running-stage"));

        assertTrue(exception.getMessage().contains("Unsupported longRunningStage"));
    }

    @Test
    void listSessionsReturnsSummariesNewestFirstAndSkipsBrokenFiles() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir);
        ConversationSession older = new ConversationSession(
                "older",
                Instant.parse("2026-04-22T08:30:00Z"),
                tempDir.resolve("older-workspace"),
                List.of(Message.system("older"), Message.user("hello")));
        ConversationSession newer = new ConversationSession(
                "newer",
                Instant.parse("2026-04-23T08:30:00Z"),
                tempDir.resolve("newer-workspace"),
                List.of(Message.system("newer")));

        storage.save(older);
        storage.save(newer);
        Files.writeString(tempDir.resolve("broken.json"), "{not-json");
        Files.setLastModifiedTime(
                storage.transcriptPath("older"),
                FileTime.from(Instant.parse("2026-04-22T10:00:00Z")));
        Files.setLastModifiedTime(
                storage.transcriptPath("newer"),
                FileTime.from(Instant.parse("2026-04-23T10:00:00Z")));

        List<SessionStorage.SessionSummary> summaries = storage.listSessions();

        assertEquals(2, summaries.size());
        assertEquals("newer", summaries.get(0).sessionId());
        assertEquals("older", summaries.get(1).sessionId());
        assertEquals(1, summaries.get(0).messageCount());
        assertEquals(2, summaries.get(1).messageCount());
        assertEquals(tempDir.resolve("newer-workspace"), summaries.get(0).workingDirectory());
        assertEquals(storage.transcriptPath("newer"), summaries.get(0).path());
    }

    private ObjectNode toolInput() {
        ObjectNode input = mapper.createObjectNode();
        input.put("pattern", "src/**/*.java");
        return input;
    }

    @Test
    void thinkingBlockRoundTrips() throws Exception {
        SessionStorage storage = new SessionStorage(tempDir);
        ConversationSession session = new ConversationSession(
                "thinking-session",
                Instant.parse("2026-05-13T12:00:00Z"),
                tempDir,
                List.of(
                        Message.user("solve this"),
                        Message.assistant(List.of(
                                new ContentBlock.ThinkingBlock("step 1: analyze\nstep 2: plan"),
                                new ContentBlock.TextBlock("Here is the answer.")))));

        storage.save(session);
        ConversationSession restored = storage.load(session.sessionId());

        Message restoredAssistant = restored.messages().get(1);
        assertEquals(2, restoredAssistant.contentBlocks().size());
        assertTrue(restoredAssistant.contentBlocks().get(0) instanceof ContentBlock.ThinkingBlock);
        ContentBlock.ThinkingBlock tb = (ContentBlock.ThinkingBlock) restoredAssistant.contentBlocks().get(0);
        assertEquals("step 1: analyze\nstep 2: plan", tb.thinking());
        assertTrue(restoredAssistant.contentBlocks().get(1) instanceof ContentBlock.TextBlock);
        ContentBlock.TextBlock txt = (ContentBlock.TextBlock) restoredAssistant.contentBlocks().get(1);
        assertEquals("Here is the answer.", txt.text());
    }

    @Test
    void v4SessionMigrationPreservesPlanItems() throws Exception {
        String v4json = """
                {
                  "schemaVersion": 4,
                  "sessionId": "v4-session",
                  "createdAt": "2026-05-01T00:00:00Z",
                  "workingDirectory": "/tmp",
                  "planMode": false,
                  "messages": [
                    {"role": "SYSTEM", "contentBlocks": [{"type": "text", "text": "Session initialized."}]},
                    {"role": "USER", "contentBlocks": [{"type": "text", "text": "hello"}]}
                  ],
                  "tasks": [
                    {
                      "id": "1",
                      "title": "Completed task",
                      "description": "",
                      "status": "COMPLETED",
                      "dependencyIds": [],
                      "createdAt": "2026-05-01T00:00:00Z",
                      "updatedAt": "2026-05-01T00:00:00Z",
                      "stopRequested": false
                    },
                    {
                      "id": "2",
                      "title": "Failed task - COMPLETED",
                      "description": "desc",
                      "status": "FAILED",
                      "dependencyIds": ["1"],
                      "createdAt": "2026-05-01T00:00:00Z",
                      "updatedAt": "2026-05-01T00:00:00Z",
                      "startedAt": "2026-05-01T00:01:00Z",
                      "completedAt": "2026-05-01T00:02:00Z",
                      "failureReason": "timed out",
                      "resultSummary": "none",
                      "stopRequested": true,
                      "agentId": "agent-1"
                    },
                    {
                      "id": "3",
                      "title": "Stopped task - COMPLETED",
                      "description": "",
                      "status": "STOPPED",
                      "dependencyIds": [],
                      "createdAt": "2026-05-01T00:00:00Z",
                      "updatedAt": "2026-05-01T00:00:00Z",
                      "stopRequested": true
                    }
                  ],
                  "todos": [
                    {"content": "Old todo", "status": "completed"}
                  ],
                  "history": []
                }
                """;

        SessionStorage storage = new SessionStorage(tempDir);
        Files.writeString(storage.transcriptPath("v4-session"), v4json);

        ConversationSession restored = storage.load("v4-session");
        var items = restored.plan().items();

        assertEquals(3, items.size());
        assertEquals("Completed task", items.get(0).title());
        assertEquals(madacode.plan.PlanStatus.COMPLETED, items.get(0).status());

        assertEquals("Failed task - COMPLETED", items.get(1).title());
        assertEquals(madacode.plan.PlanStatus.COMPLETED, items.get(1).status());
        assertEquals(List.of("1"), items.get(1).blockedBy());

        assertEquals("Stopped task - COMPLETED", items.get(2).title());
        assertEquals(madacode.plan.PlanStatus.COMPLETED, items.get(2).status());

        assertEquals(1, restored.plan().todos().size());
        assertEquals("Old todo", restored.plan().todos().getFirst().content());
    }
}
