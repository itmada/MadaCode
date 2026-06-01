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
                new CreateTaskRequest("task-001", "Refactor long-running mode", "initializing", "session-123", "INITIALIZING"));

        Path taskDir = tempDir.resolve(".mada/long-running/task-001");
        assertTrue(Files.isDirectory(taskDir));
        assertTrue(Files.isDirectory(taskDir.resolve("logs")));
        assertTrue(Files.isRegularFile(taskDir.resolve("task.json")));
        assertTrue(Files.isRegularFile(taskDir.resolve("feature_list.json")));
        assertTrue(Files.isRegularFile(taskDir.resolve("progress.txt")));
        assertTrue(Files.isRegularFile(taskDir.resolve("known-issues.json")));
        assertTrue(Files.isRegularFile(taskDir.resolve("init.sh")));

        JsonNode taskJson = mapper.readTree(taskDir.resolve("task.json").toFile());
        assertEquals("task-001", taskJson.path("id").asText());
        assertEquals("Refactor long-running mode", taskJson.path("title").asText());
        assertEquals("initializing", taskJson.path("status").asText());
        assertEquals("session-123", taskJson.path("sessionId").asText());
        assertEquals("INITIALIZING", taskJson.path("stage").asText());
        assertEquals(0, mapper.readTree(taskDir.resolve("feature_list.json").toFile()).size());
        assertEquals(0, mapper.readTree(taskDir.resolve("known-issues.json").toFile()).size());
        assertEquals("", Files.readString(taskDir.resolve("progress.txt")));
        assertTrue(Files.readString(taskDir.resolve("init.sh")).contains("Initialization hook"));
        assertEquals(metadata, store.loadTask("task-001"));
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
                new CreateTaskRequest("task-fail", "Failing task", "initializing", "session-fail", "INITIALIZING")));

        assertFalse(Files.exists(tempDir.resolve(".mada/long-running/task-fail")));
    }

    @Test
    void appendProgressAppendsTextAndRefreshesUpdatedAt() throws Exception {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest("task-002", "Track progress", "initializing", "session-456", "INITIALIZING"));
        Instant beforeAppend = store.loadTask("task-002").updatedAt();

        store.appendProgress("task-002", "step 1\n");
        store.appendProgress("task-002", "step 2\n");

        Path progressFile = tempDir.resolve(".mada/long-running/task-002/progress.txt");
        assertEquals("step 1\nstep 2\n", Files.readString(progressFile));
        assertTrue(store.loadTask("task-002").updatedAt().compareTo(beforeAppend) >= 0);
    }

    @Test
    void writeInitialFeatureListAndMarkFeaturePassed() {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest("task-003", "Feature work", "initializing", "session-789", "INITIALIZING"));

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
        store.createTask(new CreateTaskRequest("task-004", "Known issues", "initializing", "session-999", "INITIALIZING"));

        KnownIssue issue = new KnownIssue(
                "issue-1",
                "Feature list is not seeded",
                "high",
                "open",
                "INITIALIZING",
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
        store.createTask(new CreateTaskRequest("task-004b", "Known issues", "initializing", "session-999", "INITIALIZING"));

        KnownIssue resolved = new KnownIssue(
                "issue-1",
                "Already resolved",
                "high",
                "resolved",
                "INITIALIZING",
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

        assertThrows(IllegalArgumentException.class,
                () -> store.createTask(new CreateTaskRequest("../escape", "Bad task", "initializing", "session-1", "INITIALIZING")));
        assertThrows(IllegalArgumentException.class, () -> store.validateTaskDirectory("../escape"));
        assertThrows(IllegalArgumentException.class, () -> store.loadTask("bad/task"));
    }

    @Test
    void rejectsInitialFeatureListWithPassedItems() {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest("task-005", "Feature validation", "initializing", "session-111", "INITIALIZING"));

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
        store.createTask(new CreateTaskRequest("task-006", "Issue validation", "initializing", "session-222", "INITIALIZING"));

        KnownIssue first = new KnownIssue(
                "issue-dup",
                "First issue",
                "medium",
                "open",
                "INITIALIZING",
                List.of("Check logs"),
                Instant.parse("2026-06-01T09:00:00Z"),
                null);
        store.recordIssue("task-006", first);

        KnownIssue duplicate = new KnownIssue(
                "issue-dup",
                "Duplicate issue",
                "medium",
                "open",
                "INITIALIZING",
                List.of("Check logs"),
                Instant.parse("2026-06-01T09:01:00Z"),
                null);
        KnownIssue invalidStatus = new KnownIssue(
                "issue-bad",
                "Bad status",
                "medium",
                "closed",
                "INITIALIZING",
                List.of("Check logs"),
                Instant.parse("2026-06-01T09:02:00Z"),
                null);

        assertThrows(LongRunningTaskStoreException.class, () -> store.recordIssue("task-006", duplicate));
        assertThrows(LongRunningTaskStoreException.class, () -> store.recordIssue("task-006", invalidStatus));
    }

    @Test
    void validateTaskDirectoryRejectsSymlinkedFilesAndLogsDirectory() throws Exception {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest("task-007", "Symlink validation", "initializing", "session-333", "INITIALIZING"));

        Path taskDir = tempDir.resolve(".mada/long-running/task-007");
        Path outsideTask = tempDir.resolve("outside-task.json");
        Files.writeString(outsideTask, Files.readString(taskDir.resolve("task.json")));
        Files.delete(taskDir.resolve("task.json"));
        Files.createSymbolicLink(taskDir.resolve("task.json"), outsideTask);

        assertTrue(Files.isSymbolicLink(taskDir.resolve("task.json")));
        assertThrows(LongRunningTaskStoreException.class, () -> store.validateTaskDirectory("task-007"));

        Files.delete(taskDir.resolve("task.json"));
        store.createTask(new CreateTaskRequest("task-008", "Symlink logs", "initializing", "session-334", "INITIALIZING"));
        Path secondTaskDir = tempDir.resolve(".mada/long-running/task-008");
        Path outsideLogs = tempDir.resolve("outside-logs");
        Files.createDirectories(outsideLogs);
        Files.delete(secondTaskDir.resolve("logs"));
        Files.createSymbolicLink(secondTaskDir.resolve("logs"), outsideLogs);

        assertTrue(Files.isSymbolicLink(secondTaskDir.resolve("logs")));
        assertFalse(Files.isDirectory(secondTaskDir.resolve("logs"), LinkOption.NOFOLLOW_LINKS));
        assertThrows(LongRunningTaskStoreException.class, () -> store.validateTaskDirectory("task-008"));
    }
}
