package madacode.longrunning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunningTaskStoreTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void createTaskCreatesExpectedDirectoryAndDefaultFiles() throws Exception {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);

        LongRunningTaskMetadata metadata = store.createTask(
                new CreateTaskRequest("task-001", "Refactor long-running mode", "DRAFT", null, "session-123", null));

        Path taskDir = tempDir.resolve(".mada/long-running/task-001");
        assertTrue(Files.isDirectory(taskDir));
        assertTrue(Files.isDirectory(taskDir.resolve("logs")));
        assertTrue(Files.isRegularFile(taskDir.resolve("task.json")));
        assertTrue(Files.isRegularFile(taskDir.resolve("feature_list.json")));
        assertTrue(Files.isRegularFile(taskDir.resolve("task.json")));
        assertTrue(Files.isRegularFile(taskDir.resolve("progress.txt")));
        assertTrue(Files.isRegularFile(taskDir.resolve("known_issues.json")));
        assertTrue(Files.isRegularFile(taskDir.resolve("init.sh")));
        assertTrue(Files.isRegularFile(taskDir.resolve("logs/events.jsonl")));

        JsonNode taskJson = mapper.readTree(taskDir.resolve("task.json").toFile());
        assertEquals("task-001", taskJson.path("id").asText());
        assertEquals("Refactor long-running mode", taskJson.path("title").asText());
        assertEquals("DRAFT", taskJson.path("status").asText());
        assertEquals("session-123", taskJson.path("controlSessionId").asText());
        assertFalse(taskJson.has("stage"));
        assertEquals(0, mapper.readTree(taskDir.resolve("feature_list.json").toFile()).size());
        assertEquals(0, mapper.readTree(taskDir.resolve("known_issues.json").toFile()).size());
        assertEquals("", Files.readString(taskDir.resolve("progress.txt")));
        assertEquals("", Files.readString(taskDir.resolve("logs/events.jsonl")));
        assertTrue(Files.readString(taskDir.resolve("init.sh")).contains("Initialization hook"));
        assertEquals(metadata, store.loadTask("task-001"));
    }

    @Test
    void writeAndReadPlanSummary() {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest(
                "task-plan", "Plan summary", "DRAFT", null, "session-plan", null));

        store.writePlanSummary("task-plan", "Build the backend first.");

        assertEquals("Build the backend first.\n", store.readPlanSummary("task-plan").orElseThrow());
    }

    @Test
    void appendEventWritesReadableJsonl() throws Exception {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest(
                "task-events", "Structured events", "RUNNING", null, "session-events", null));

        store.appendEvent("task-events", new LongRunningTaskEvent(
                Instant.parse("2026-06-01T12:00:00Z"),
                "task_update",
                "task-events",
                "session-events",
                "RUNNING",
                "append_progress",
                true,
                "Progress appended.",
                Map.of("featureId", "feature-1")));

        Path eventsFile = tempDir.resolve(".mada/long-running/task-events/logs/events.jsonl");
        List<String> lines = Files.readAllLines(eventsFile);
        assertEquals(1, lines.size());
        JsonNode raw = mapper.readTree(lines.getFirst());
        assertEquals("task_update", raw.path("type").asText());
        assertEquals("append_progress", raw.path("action").asText());
        assertTrue(raw.path("success").asBoolean());

        List<LongRunningTaskEvent> events = store.readEvents("task-events");
        assertEquals(1, events.size());
        LongRunningTaskEvent event = events.getFirst();
        assertEquals("task_update", event.type());
        assertEquals("feature-1", event.details().get("featureId"));
    }

    @Test
    void readEventsIgnoresPartialTrailingJsonlLine() throws Exception {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest(
                "task-partial-events", "Structured events", "RUNNING", null, "session-events", null));

        store.appendEvent("task-partial-events", LongRunningTaskEvent.of(
                "launcher_started",
                "task-partial-events",
                "session-events",
                "RUNNING",
                null,
                true,
                "Launcher started.",
                Map.of()));
        Path eventsFile = tempDir.resolve(".mada/long-running/task-partial-events/logs/events.jsonl");
        Files.writeString(eventsFile, "{\"timestamp\":\"partial\"", java.nio.file.StandardOpenOption.APPEND);

        List<LongRunningTaskEvent> events = store.readEvents("task-partial-events");

        assertEquals(1, events.size());
        assertEquals("launcher_started", events.getFirst().type());
    }

    @Test
    void validateTaskDirectoryCreatesMissingEventLogForLegacyTasks() throws Exception {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest(
                "task-legacy-events", "Legacy", "RUNNING", null, "session-legacy", null));
        Path eventsFile = tempDir.resolve(".mada/long-running/task-legacy-events/logs/events.jsonl");
        Files.delete(eventsFile);

        store.validateTaskDirectory("task-legacy-events");

        assertTrue(Files.isRegularFile(eventsFile));
        assertEquals("", Files.readString(eventsFile));
    }

    @Test
    void writeCheckpointPersistsSnapshotAndUpdatesInitScript() throws Exception {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest(
                "task-checkpoint", "Checkpoint", "RUNNING", null, "session-cp", null));
        LongRunningWorkspaceCheckpoint checkpoint = new LongRunningWorkspaceCheckpoint(
                Instant.parse("2026-06-01T12:00:00Z"),
                tempDir,
                false,
                null,
                null,
                null,
                false,
                "");

        store.writeCheckpoint("task-checkpoint", checkpoint);

        Path taskDir = tempDir.resolve(".mada/long-running/task-checkpoint");
        JsonNode raw = mapper.readTree(taskDir.resolve("checkpoint.json").toFile());
        assertEquals("2026-06-01T12:00:00Z", raw.path("capturedAt").asText());
        assertFalse(raw.path("gitRepository").asBoolean());
        assertTrue(Files.readString(taskDir.resolve("init.sh"))
                .contains("No git repository detected"));
        assertTrue(store.readCheckpoint("task-checkpoint").isPresent());
    }

    @Test
    void createTaskCleansUpWhenWriteFails() throws Exception {
        ObjectMapper failingMapper = new ObjectMapper() {
            @Override
            public ObjectWriter writerWithDefaultPrettyPrinter() {
                return new ObjectWriter(this, getSerializationConfig()) {
                    @Override
                    public void writeValue(File resultFile, Object value) throws IOException {
                        throw new IOException("boom");
                    }
                };
            }
        };
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir, failingMapper);

        assertThrows(LongRunningTaskStoreException.class, () -> store.createTask(
                new CreateTaskRequest("task-fail", "Failing task", "DRAFT", null, "session-fail", null)));

        assertFalse(Files.exists(tempDir.resolve(".mada/long-running/task-fail")));
    }

    @Test
    void appendProgressAppendsTextAndRefreshesUpdatedAt() throws Exception {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest("task-002", "Track progress", "DRAFT", null, "session-456", null));
        Instant beforeAppend = store.loadTask("task-002").updatedAt();

        store.appendProgress("task-002", "step 1\n");
        store.appendProgress("task-002", "step 2\n");

        Path progressFile = tempDir.resolve(".mada/long-running/task-002/progress.txt");
        assertEquals("step 1\nstep 2\n", Files.readString(progressFile));
        assertTrue(store.loadTask("task-002").updatedAt().compareTo(beforeAppend) >= 0);
    }

    @Test
    void markTaskExecutingAcceptsDraft() {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest(
                "task-executing", "Draft plan", "DRAFT", null, "session-run", null));
        store.writeInitialFeatureList("task-executing", List.of(
                new FeatureItem("feature-a", "core", "high", "Feature A", List.of(), List.of("verify"), false)));

        LongRunningTaskMetadata executing = store.markTaskExecuting("task-executing");

        assertEquals("RUNNING", executing.status());
        assertEquals("RUNNING", executing.status());
    }

    @Test
    void writeInitialFeatureListAndMarkFeaturePassed() {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest("task-003", "Feature work", "DRAFT", null, "session-789", null));

        store.writeInitialFeatureList("task-003", List.of(
                new FeatureItem(
                        "feature-a",
                        "backend",
                        "high",
                        "Create storage layer",
                        List.of(),
                        List.of("Run storage tests"),
                        false),
                new FeatureItem(
                        "feature-b",
                        "backend",
                        "medium",
                        "Wire progress file",
                        List.of("feature-a"),
                        List.of("Inspect progress.txt"),
                        false)));

        FeatureItem updated = store.markFeaturePassed("task-003", "feature-a");

        assertTrue(updated.passes());
        List<FeatureItem> features = store.readFeatureList("task-003");
        assertTrue(features.stream().filter(feature -> feature.id().equals("feature-a")).findFirst().orElseThrow().passes());
        assertFalse(features.stream().filter(feature -> feature.id().equals("feature-b")).findFirst().orElseThrow().passes());
    }

    @Test
    void recordIssueAndResolveIt() {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest("task-004", "Known issues", "DRAFT", null, "session-999", null));

        KnownIssue issue = new KnownIssue(
                "issue-1",
                "Feature list is not seeded",
                "high",
                "open",
                "DRAFT",
                List.of("Re-run initialization"),
                Instant.parse("2026-06-01T08:00:00Z"),
                null);

        store.recordIssue("task-004", issue);
        KnownIssue resolved = store.markIssueResolved("task-004", "issue-1");

        assertEquals("resolved", resolved.status());
        assertNotNull(resolved.resolvedAt());
        List<KnownIssue> issues = store.readKnownIssues("task-004");
        KnownIssue persisted = issues.getFirst();
        assertEquals("resolved", persisted.status());
        assertNotNull(persisted.resolvedAt());
    }

    @Test
    void resolvingResolvedIssueKeepsOriginalResolvedAt() {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest("task-004b", "Known issues", "DRAFT", null, "session-999", null));

        KnownIssue resolved = new KnownIssue(
                "issue-1",
                "Already resolved",
                "high",
                "resolved",
                "DRAFT",
                List.of("Re-run initialization"),
                Instant.parse("2026-06-01T08:00:00Z"),
                Instant.parse("2026-06-01T08:30:00Z"));

        store.recordIssue("task-004b", resolved);
        KnownIssue unchanged = store.markIssueResolved("task-004b", "issue-1");

        assertEquals(Instant.parse("2026-06-01T08:30:00Z"), unchanged.resolvedAt());
    }

    @Test
    void rejectsTraversalAndUnsafeTaskIds() {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);

        assertThrows(LongRunningTaskStoreException.class,
                () -> store.createTask(new CreateTaskRequest("../escape", "Bad task", "DRAFT", null, "session-1", null)));
        assertThrows(LongRunningTaskStoreException.class, () -> store.validateTaskDirectory("../escape"));
        assertThrows(LongRunningTaskStoreException.class, () -> store.loadTask("bad/task"));
    }

    @Test
    void rejectsInitialFeatureListWithPassedItems() {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest("task-005", "Feature validation", "DRAFT", null, "session-111", null));

        assertThrows(LongRunningTaskStoreException.class, () -> store.writeInitialFeatureList("task-005", List.of(
                new FeatureItem(
                        "feature-a",
                        "backend",
                        "high",
                        "Already passed",
                        List.of(),
                        List.of("Should fail"),
                        true))));
    }

    @Test
    void rejectsDuplicateKnownIssueIdsAndInvalidStatuses() {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest("task-006", "Issue validation", "DRAFT", null, "session-222", null));

        KnownIssue first = new KnownIssue(
                "issue-dup",
                "First issue",
                "medium",
                "open",
                "DRAFT",
                List.of("Check logs"),
                Instant.parse("2026-06-01T09:00:00Z"),
                null);
        store.recordIssue("task-006", first);

        KnownIssue duplicate = new KnownIssue(
                "issue-dup",
                "Duplicate issue",
                "medium",
                "open",
                "DRAFT",
                List.of("Check logs"),
                Instant.parse("2026-06-01T09:01:00Z"),
                null);
        KnownIssue invalidStatus = new KnownIssue(
                "issue-bad",
                "Bad status",
                "medium",
                "closed",
                "DRAFT",
                List.of("Check logs"),
                Instant.parse("2026-06-01T09:02:00Z"),
                null);

        assertThrows(LongRunningTaskStoreException.class, () -> store.recordIssue("task-006", duplicate));
        assertThrows(LongRunningTaskStoreException.class, () -> store.recordIssue("task-006", invalidStatus));
    }

    @Test
    void validateTaskDirectoryRejectsSymlinkedFilesAndLogsDirectory() throws Exception {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest("task-007", "Symlink validation", "DRAFT", null, "session-333", null));

        Path taskDir = tempDir.resolve(".mada/long-running/task-007");
        Path outsideTask = tempDir.resolve("outside-task.json");
        Files.writeString(outsideTask, Files.readString(taskDir.resolve("task.json")));
        Files.delete(taskDir.resolve("task.json"));
        Files.createSymbolicLink(taskDir.resolve("task.json"), outsideTask);

        assertTrue(Files.isSymbolicLink(taskDir.resolve("task.json")));
        assertThrows(LongRunningTaskStoreException.class, () -> store.validateTaskDirectory("task-007"));

        Files.delete(taskDir.resolve("task.json"));
        store.createTask(new CreateTaskRequest("task-008", "Symlink logs", "DRAFT", null, "session-334", null));
        Path secondTaskDir = tempDir.resolve(".mada/long-running/task-008");
        Path outsideLogs = tempDir.resolve("outside-logs");
        Files.createDirectories(outsideLogs);
        Files.delete(secondTaskDir.resolve("logs/events.jsonl"));
        Files.delete(secondTaskDir.resolve("logs"));
        Files.createSymbolicLink(secondTaskDir.resolve("logs"), outsideLogs);

        assertTrue(Files.isSymbolicLink(secondTaskDir.resolve("logs")));
        assertFalse(Files.isDirectory(secondTaskDir.resolve("logs"), LinkOption.NOFOLLOW_LINKS));
        assertThrows(LongRunningTaskStoreException.class, () -> store.validateTaskDirectory("task-008"));
    }
}
