package madacode.longrunning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.util.AtomicFiles;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

final class LongRunningTaskRepository {

    static final String TASK_FILE = "task.json";
    static final String FEATURE_LIST_FILE = "feature_list.json";
    static final String PROGRESS_FILE = "progress.txt";
    static final String KNOWN_ISSUES_FILE = "known_issues.json";
    static final String INIT_SCRIPT_FILE = "init.sh";
    static final String CHECKPOINT_FILE = "checkpoint.json";
    static final String LOGS_DIR = "logs";
    static final String EVENTS_FILE = "events.jsonl";
    static final String EXECUTION_LOCK_FILE = ".execution.lock";
    static final String TASK_STATE_LOCK_FILE = ".state.lock";

    private static final Pattern SAFE_TASK_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final Set<String> ALLOWED_ISSUE_STATUSES = Set.of("open", "resolved", "blocked");
    private static final Set<String> ALLOWED_TASK_STATUSES = Set.of("DRAFT", "RUNNING", "INTERRUPT", "DONE");
    private static final String ROOT_DIR = ".mada/long-running";
    private static final String DEFAULT_INIT_SCRIPT = """
            #!/usr/bin/env bash
            set -euo pipefail

            # Initialization hook for this long-running task.
            """;

    private final Path rootDirectory;
    private final ObjectMapper mapper;
    private LongRunningEventLog eventLog;

    LongRunningTaskRepository(Path projectDirectory, ObjectMapper mapper) {
        this.rootDirectory = Objects.requireNonNull(projectDirectory, "projectDirectory")
                .toAbsolutePath()
                .normalize()
                .resolve(ROOT_DIR)
                .normalize();
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    void attachEventLog(LongRunningEventLog eventLog) {
        this.eventLog = Objects.requireNonNull(eventLog, "eventLog");
    }

    LongRunningTaskMetadata createTask(CreateTaskRequest request) {
        Objects.requireNonNull(request, "request");
        String taskId = validateTaskId(request.id());
        Path taskDirectory = taskDirectoryPath(taskId);
        if (Files.exists(taskDirectory)) {
            throw new LongRunningTaskStoreException("Task directory already exists for " + taskId);
        }

        Instant now = Instant.now();
        LongRunningTaskMetadata metadata = new LongRunningTaskMetadata(
                taskId,
                request.title(),
                request.status(),
                request.reason(),
                "RUNNING".equals(request.status()) ? now : null,
                now,
                now,
                request.controlSessionId(),
                request.planSummary());

        try {
            Files.createDirectories(rootDirectory);
            Path stagingDirectory = Files.createTempDirectory(rootDirectory, taskId + ".");
            boolean moved = false;
            try {
                Files.createDirectories(stagingDirectory.resolve(LOGS_DIR));
                writeJsonAtomically(stagingDirectory.resolve(TASK_FILE), serializeTask(metadata));
                writeJsonAtomically(stagingDirectory.resolve(FEATURE_LIST_FILE), mapper.createArrayNode());
                writeStringAtomically(stagingDirectory.resolve(PROGRESS_FILE), "");
                writeJsonAtomically(stagingDirectory.resolve(KNOWN_ISSUES_FILE), mapper.createArrayNode());
                writeJsonAtomically(stagingDirectory.resolve(CHECKPOINT_FILE), mapper.createObjectNode());
                writeStringAtomically(stagingDirectory.resolve(INIT_SCRIPT_FILE), DEFAULT_INIT_SCRIPT);
                writeStringAtomically(stagingDirectory.resolve(LOGS_DIR).resolve(EVENTS_FILE), "");
                moveDirectoryIntoPlace(stagingDirectory, taskDirectory);
                moved = true;
            } finally {
                if (!moved) {
                    deleteRecursively(stagingDirectory);
                }
            }
            return metadata;
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException("Failed to create long-running task " + taskId, exception);
        }
    }

    LongRunningTaskMetadata loadTask(String taskId) {
        Path directory = validateTaskDirectory(taskId);
        return readTaskMetadata(directory.resolve(TASK_FILE), validateTaskId(taskId));
    }

    LongRunningTaskMetadata updateTaskShell(
            String taskId,
            String title,
            String status,
            String reason,
            Instant executionStarted,
            String controlSessionId,
            String planSummary) {
        Path directory = validateTaskDirectory(taskId);
        LongRunningTaskMetadata metadata = loadTask(taskId);
        LongRunningTaskMetadata updated = new LongRunningTaskMetadata(
                metadata.id(),
                title == null || title.isBlank() ? metadata.title() : title.strip(),
                status == null || status.isBlank() ? metadata.status() : status.strip().toUpperCase(),
                reason,
                executionStarted,
                metadata.createdAt(),
                Instant.now(),
                controlSessionId == null || controlSessionId.isBlank()
                        ? metadata.controlSessionId()
                        : controlSessionId.strip(),
                planSummary);
        try {
            writeJsonAtomically(directory.resolve(TASK_FILE), serializeTask(updated));
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException("Failed to update task shell for " + taskId, exception);
        }
        return updated;
    }

    List<FeatureItem> readFeatureList(String taskId) {
        Path directory = validateTaskDirectory(taskId);
        return readFeatures(directory.resolve(FEATURE_LIST_FILE));
    }

    Optional<String> readPlanSummary(String taskId) {
        String safeTaskId = validateTaskId(taskId);
        LongRunningTaskMetadata metadata = loadTask(safeTaskId);
        if (metadata.planSummary() == null || metadata.planSummary().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(metadata.planSummary() + System.lineSeparator());
    }

    void writePlanSummary(String taskId, String plan) {
        String safeTaskId = validateTaskId(taskId);
        String value = plan == null ? "" : plan.strip();
        updateTaskMetadata(safeTaskId, metadata -> new LongRunningTaskMetadata(
                metadata.id(),
                metadata.title(),
                metadata.status(),
                metadata.reason(),
                metadata.executionStarted(),
                metadata.createdAt(),
                Instant.now(),
                metadata.controlSessionId(),
                value.isBlank() ? null : value));
    }

    void writeInitialFeatureList(String taskId, List<FeatureItem> features) {
        Path directory = validateTaskDirectory(taskId);
        List<FeatureItem> existing = readFeatures(directory.resolve(FEATURE_LIST_FILE));
        if (!existing.isEmpty()) {
            throw new LongRunningTaskStoreException("Initial feature list already exists for " + taskId);
        }
        List<FeatureItem> validated = validateFeatureList(features, false);
        validateFeatureDependencies(validated, false);
        writeFeatures(directory.resolve(FEATURE_LIST_FILE), validated, taskId);
        updateTaskTimestamp(taskId, Instant.now());
    }

    void replaceFeatureList(String taskId, List<FeatureItem> features) {
        Path directory = validateTaskDirectory(taskId);
        List<FeatureItem> validated = validateFeatureList(features, true);
        validateFeatureDependencies(validated, true);
        writeFeatures(directory.resolve(FEATURE_LIST_FILE), validated, taskId);
        updateTaskTimestamp(taskId, Instant.now());
    }

    FeatureItem markFeaturePassed(String taskId, String featureId, List<String> verificationEvidence) {
        requireNonBlank(featureId, "featureId");
        Path directory = validateTaskDirectory(taskId);
        List<FeatureItem> features = readFeatures(directory.resolve(FEATURE_LIST_FILE));

        FeatureItem target = features.stream()
                .filter(f -> f.id().equals(featureId))
                .findFirst()
                .orElseThrow(() -> new LongRunningTaskStoreException(
                        "Unknown feature id " + featureId + " for task " + taskId));

        if (target.passes()) {
            return target;
        }
        List<String> evidence = List.copyOf(Objects.requireNonNullElse(verificationEvidence, List.of()));
        ensureListItemsPresent(evidence, "feature.verificationEvidence");
        if (!target.verificationSteps().isEmpty() && evidence.isEmpty()) {
            throw new LongRunningTaskStoreException(
                    "Feature " + featureId + " cannot be passed without verification evidence");
        }

        for (String depId : target.dependsOn()) {
            FeatureItem dep = features.stream()
                    .filter(f -> f.id().equals(depId))
                    .findFirst()
                    .orElseThrow(() -> new LongRunningTaskStoreException(
                            "Feature " + featureId + " depends on unknown feature " + depId));
            if (!dep.passes()) {
                throw new LongRunningTaskStoreException(
                        "Feature " + featureId + " cannot be passed: dependency " + depId + " has not passed yet");
            }
        }

        List<KnownIssue> issues = readKnownIssuesFile(directory.resolve(KNOWN_ISSUES_FILE));
        boolean hasActiveIssue = issues.stream()
                .anyMatch(issue -> "open".equals(issue.status()) || "blocked".equals(issue.status()));
        if (hasActiveIssue) {
            throw new LongRunningTaskStoreException(
                    "Cannot mark feature " + featureId + " as passed: resolve open or blocked known issues first");
        }

        List<FeatureItem> updated = new ArrayList<>(features.size());
        for (FeatureItem feature : features) {
            if (feature.id().equals(featureId)) {
                updated.add(new FeatureItem(
                        feature.id(),
                        feature.category(),
                        feature.priority(),
                        feature.description(),
                        feature.dependsOn(),
                        feature.verificationSteps(),
                        true,
                        evidence));
            } else {
                updated.add(feature);
            }
        }
        writeFeatures(directory.resolve(FEATURE_LIST_FILE), updated, taskId);
        updateTaskTimestamp(taskId, Instant.now());
        return updated.stream().filter(f -> f.id().equals(featureId)).findFirst().orElseThrow();
    }

    List<KnownIssue> readKnownIssues(String taskId) {
        Path directory = validateTaskDirectory(taskId);
        return readKnownIssuesFile(directory.resolve(KNOWN_ISSUES_FILE));
    }

    void replaceKnownIssues(String taskId, List<KnownIssue> issues) {
        Path directory = validateTaskDirectory(taskId);
        writeKnownIssues(directory.resolve(KNOWN_ISSUES_FILE), issues, taskId);
        updateTaskTimestamp(taskId, Instant.now());
    }

    void writeCheckpoint(String taskId, LongRunningWorkspaceCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        String safeTaskId = validateTaskId(taskId);
        Path directory = validateTaskDirectory(safeTaskId);
        try {
            writeJsonAtomically(directory.resolve(CHECKPOINT_FILE), serializeCheckpoint(checkpoint));
            writeStringAtomically(directory.resolve(INIT_SCRIPT_FILE), initScript(checkpoint));
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException("Failed to write workspace checkpoint for " + safeTaskId, exception);
        }
    }

    Optional<LongRunningWorkspaceCheckpoint> readCheckpoint(String taskId) {
        String safeTaskId = validateTaskId(taskId);
        Path directory = validateTaskDirectory(safeTaskId);
        Path checkpointFile = directory.resolve(CHECKPOINT_FILE);
        if (!Files.isRegularFile(checkpointFile, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        try {
            return Optional.of(deserializeCheckpoint(mapper.readTree(checkpointFile.toFile())));
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException("Failed to read workspace checkpoint for " + safeTaskId, exception);
        }
    }

    KnownIssue recordIssue(String taskId, KnownIssue issue) {
        Objects.requireNonNull(issue, "issue");
        Path directory = validateTaskDirectory(taskId);
        List<KnownIssue> issues = new ArrayList<>(readKnownIssuesFile(directory.resolve(KNOWN_ISSUES_FILE)));
        if (issues.stream().anyMatch(existing -> existing.id().equals(issue.id()))) {
            throw new LongRunningTaskStoreException("Known issue " + issue.id() + " already exists for task " + taskId);
        }
        validateKnownIssue(issue);
        issues.add(issue);
        writeKnownIssues(directory.resolve(KNOWN_ISSUES_FILE), issues, taskId);
        updateTaskTimestamp(taskId, Instant.now());
        return issue;
    }

    KnownIssue markIssueResolved(String taskId, String issueId) {
        requireNonBlank(issueId, "issueId");
        Path directory = validateTaskDirectory(taskId);
        List<KnownIssue> issues = readKnownIssuesFile(directory.resolve(KNOWN_ISSUES_FILE));
        List<KnownIssue> updated = new ArrayList<>(issues.size());
        KnownIssue changed = null;
        Instant now = Instant.now();
        for (KnownIssue issue : issues) {
            if (issue.id().equals(issueId)) {
                changed = "resolved".equals(issue.status())
                        ? issue
                        : new KnownIssue(
                                issue.id(),
                                issue.description(),
                                issue.severity(),
                                "resolved",
                                issue.discoveredIn(),
                                issue.verificationSteps(),
                                issue.createdAt(),
                                now);
                updated.add(changed);
            } else {
                updated.add(issue);
            }
        }
        if (changed == null) {
            throw new LongRunningTaskStoreException("Unknown issue id " + issueId + " for task " + taskId);
        }
        writeKnownIssues(directory.resolve(KNOWN_ISSUES_FILE), updated, taskId);
        updateTaskTimestamp(taskId, now);
        return changed;
    }

    KnownIssue updateIssueStatus(String taskId, String issueId, String newStatus) {
        requireNonBlank(issueId, "issueId");
        requireNonBlank(newStatus, "newStatus");
        if (!ALLOWED_ISSUE_STATUSES.contains(newStatus)) {
            throw new LongRunningTaskStoreException("Unsupported issue status: " + newStatus);
        }
        Path directory = validateTaskDirectory(taskId);
        List<KnownIssue> issues = readKnownIssuesFile(directory.resolve(KNOWN_ISSUES_FILE));
        List<KnownIssue> updated = new ArrayList<>(issues.size());
        KnownIssue changed = null;
        Instant now = Instant.now();
        for (KnownIssue issue : issues) {
            if (issue.id().equals(issueId)) {
                String currentStatus = issue.status();
                if (currentStatus.equals(newStatus)) {
                    changed = issue;
                } else if ("resolved".equals(currentStatus)) {
                    throw new LongRunningTaskStoreException(
                            "Cannot change status of resolved issue " + issueId);
                } else if ("open".equals(currentStatus) && "blocked".equals(newStatus)) {
                    changed = new KnownIssue(issue.id(), issue.description(), issue.severity(),
                            "blocked", issue.discoveredIn(), issue.verificationSteps(),
                            issue.createdAt(), null);
                } else if ("blocked".equals(currentStatus) && "open".equals(newStatus)) {
                    changed = new KnownIssue(issue.id(), issue.description(), issue.severity(),
                            "open", issue.discoveredIn(), issue.verificationSteps(),
                            issue.createdAt(), null);
                } else if (("open".equals(currentStatus) || "blocked".equals(currentStatus))
                        && "resolved".equals(newStatus)) {
                    changed = new KnownIssue(issue.id(), issue.description(), issue.severity(),
                            "resolved", issue.discoveredIn(), issue.verificationSteps(),
                            issue.createdAt(), now);
                } else {
                    throw new LongRunningTaskStoreException(
                            "Invalid issue status transition: " + currentStatus + " -> " + newStatus);
                }
                updated.add(changed);
            } else {
                updated.add(issue);
            }
        }
        if (changed == null) {
            throw new LongRunningTaskStoreException("Unknown issue id " + issueId + " for task " + taskId);
        }
        writeKnownIssues(directory.resolve(KNOWN_ISSUES_FILE), updated, taskId);
        updateTaskTimestamp(taskId, now);
        return changed;
    }

    void appendProgress(String taskId, String text) {
        requireNonBlank(text, "text");
        Path directory = validateTaskDirectory(taskId);
        Path progressFile = directory.resolve(PROGRESS_FILE);
        synchronized (appendLock(progressFile)) {
            try (FileChannel channel = FileChannel.open(
                    progressFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
                 var ignored = channel.lock()) {
                ByteBuffer bytes = StandardCharsets.UTF_8.encode(text);
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(false);
                updateTaskTimestamp(taskId, Instant.now());
            } catch (IOException exception) {
                throw new LongRunningTaskStoreException("Failed to append progress for task " + taskId, exception);
            }
        }
    }

    String readProgress(String taskId) {
        Path directory = validateTaskDirectory(taskId);
        try {
            return Files.readString(directory.resolve(PROGRESS_FILE));
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException("Failed to read progress for task " + taskId, exception);
        }
    }

    LongRunningTaskMetadata markTaskCompleted(String taskId) {
        requireNonBlank(taskId, "taskId");
        Path directory = validateTaskDirectory(taskId);
        LongRunningTaskMetadata metadata = loadTask(taskId);
        if (!"RUNNING".equals(metadata.status())) {
            throw new LongRunningTaskStoreException(
                    "Task " + taskId + " cannot be completed: current status is " + metadata.status());
        }
        validateTaskCompletionPreconditions(taskId, directory);
        LongRunningTaskMetadata updated = new LongRunningTaskMetadata(
                metadata.id(),
                metadata.title(),
                "DONE",
                "task_completed",
                metadata.executionStarted(),
                metadata.createdAt(),
                Instant.now(),
                metadata.controlSessionId(),
                metadata.planSummary());
        try {
            writeJsonAtomically(directory.resolve(TASK_FILE), serializeTask(updated));
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException(
                    "Failed to mark task " + taskId + " as completed", exception);
        }
        return updated;
    }

    LongRunningTaskMetadata markTaskExecuting(String taskId) {
        requireNonBlank(taskId, "taskId");
        Path directory = validateTaskDirectory(taskId);
        LongRunningTaskMetadata metadata = loadTask(taskId);
        if ("RUNNING".equals(metadata.status())) {
            return metadata;
        }
        if (!"DRAFT".equals(metadata.status()) && !"INTERRUPT".equals(metadata.status())) {
            throw new LongRunningTaskStoreException(
                    "Task " + taskId + " cannot enter execution: status="
                            + metadata.status());
        }
        requireReadyForExecution(taskId, directory);
        return writeTaskLifecycle(directory, metadata, "RUNNING", "RUNNING",
                "Failed to mark task " + taskId + " as executing");
    }

    LongRunningTaskMetadata markTaskInterrupted(String taskId, String reason) {
        requireNonBlank(taskId, "taskId");
        Path directory = validateTaskDirectory(taskId);
        LongRunningTaskMetadata metadata = loadTask(taskId);
        String effectiveReason = reason == null || reason.isBlank() ? "user_interrupted" : reason.strip();
        if ("INTERRUPT".equals(metadata.status())
                && effectiveReason.equals(metadata.reason())) {
            return metadata;
        }
        if (!"DRAFT".equals(metadata.status())
                && !"RUNNING".equals(metadata.status())
                && !"INTERRUPT".equals(metadata.status())) {
            throw new LongRunningTaskStoreException(
                    "Task " + taskId + " cannot enter interrupt state: status="
                            + metadata.status());
        }
        return writeTaskLifecycle(directory, metadata, "INTERRUPT", effectiveReason,
                "Failed to mark task " + taskId + " as interrupted");
    }

    void requireRunning(String taskId) {
        LongRunningTaskMetadata metadata = loadTask(taskId);
        if (!"RUNNING".equals(metadata.status())) {
            throw new LongRunningTaskStoreException(
                    "Task " + taskId + " is not running: current status is " + metadata.status());
        }
    }

    LongRunningTaskMetadata cancelTask(String taskId) {
        requireNonBlank(taskId, "taskId");
        Path directory = validateTaskDirectory(taskId);
        LongRunningTaskMetadata metadata = loadTask(taskId);
        if ("DONE".equals(metadata.status())) {
            throw new LongRunningTaskStoreException(
                    "Task " + taskId + " cannot be cancelled: current status is " + metadata.status());
        }
        LongRunningTaskMetadata updated = new LongRunningTaskMetadata(
                metadata.id(),
                metadata.title(),
                "DONE",
                "cancelled",
                metadata.executionStarted(),
                metadata.createdAt(),
                Instant.now(),
                metadata.controlSessionId(),
                metadata.planSummary());
        try {
            writeJsonAtomically(directory.resolve(TASK_FILE), serializeTask(updated));
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException(
                    "Failed to cancel task " + taskId, exception);
        }
        return updated;
    }

    LongRunningTaskMetadata markTaskFailed(String taskId) {
        requireNonBlank(taskId, "taskId");
        Path directory = validateTaskDirectory(taskId);
        LongRunningTaskMetadata metadata = loadTask(taskId);
        if ("DONE".equals(metadata.status())) {
            throw new LongRunningTaskStoreException(
                    "Task " + taskId + " cannot fail: current status is " + metadata.status());
        }
        LongRunningTaskMetadata updated = new LongRunningTaskMetadata(
                metadata.id(),
                metadata.title(),
                "DONE",
                "failure",
                metadata.executionStarted(),
                metadata.createdAt(),
                Instant.now(),
                metadata.controlSessionId(),
                metadata.planSummary());
        try {
            writeJsonAtomically(directory.resolve(TASK_FILE), serializeTask(updated));
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException(
                    "Failed to mark task " + taskId + " as failed", exception);
        }
        return updated;
    }

    Path validateTaskDirectory(String taskId) {
        String safeTaskId = validateTaskId(taskId);
        Path directory = taskDirectoryPath(safeTaskId);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new LongRunningTaskStoreException("Task directory not found for " + safeTaskId);
        }
        requireRegularFile(directory.resolve(TASK_FILE), TASK_FILE, safeTaskId);
        requireRegularFile(directory.resolve(FEATURE_LIST_FILE), FEATURE_LIST_FILE, safeTaskId);
        requireRegularFile(directory.resolve(PROGRESS_FILE), PROGRESS_FILE, safeTaskId);
        requireRegularFile(directory.resolve(KNOWN_ISSUES_FILE), KNOWN_ISSUES_FILE, safeTaskId);
        requireRegularFile(directory.resolve(CHECKPOINT_FILE), CHECKPOINT_FILE, safeTaskId);
        requireRegularFile(directory.resolve(INIT_SCRIPT_FILE), INIT_SCRIPT_FILE, safeTaskId);
        Path logs = directory.resolve(LOGS_DIR);
        if (!Files.isDirectory(logs, LinkOption.NOFOLLOW_LINKS)) {
            throw new LongRunningTaskStoreException("Missing logs directory for task " + safeTaskId);
        }
        eventLog().ensureEventLogFile(logs.resolve(EVENTS_FILE), safeTaskId);
        readTaskMetadata(directory.resolve(TASK_FILE), safeTaskId);
        readFeatures(directory.resolve(FEATURE_LIST_FILE));
        readKnownIssuesFile(directory.resolve(KNOWN_ISSUES_FILE));
        return directory;
    }

    Path taskDirectoryPath(String taskId) {
        return rootDirectory.resolve(validateTaskId(taskId)).normalize();
    }

    Path eventsFile(String taskId) {
        return validateTaskDirectory(taskId).resolve(LOGS_DIR).resolve(EVENTS_FILE);
    }

    String validateTaskId(String taskId) {
        String safeTaskId = requireNonBlank(taskId, "taskId");
        if (!SAFE_TASK_ID.matcher(safeTaskId).matches()) {
            throw new LongRunningTaskStoreException("Invalid task id: " + taskId);
        }
        return safeTaskId;
    }

    private void validateTaskCompletionPreconditions(String taskId, Path directory) {
        List<FeatureItem> features = readFeatures(directory.resolve(FEATURE_LIST_FILE));
        if (features.isEmpty()) {
            throw new LongRunningTaskStoreException(
                    "Task " + taskId + " cannot be completed: feature list is empty");
        }
        List<String> incompleteFeatures = features.stream()
                .filter(feature -> !feature.passes())
                .map(FeatureItem::id)
                .toList();
        if (!incompleteFeatures.isEmpty()) {
            throw new LongRunningTaskStoreException(
                    "Task " + taskId + " cannot be completed: incomplete features "
                            + String.join(", ", incompleteFeatures));
        }
        List<String> unverifiedFeatures = features.stream()
                .filter(feature -> !feature.verificationSteps().isEmpty()
                        && feature.verificationEvidence().isEmpty())
                .map(FeatureItem::id)
                .toList();
        if (!unverifiedFeatures.isEmpty()) {
            throw new LongRunningTaskStoreException(
                    "Task " + taskId + " cannot be completed: missing verification evidence for "
                            + String.join(", ", unverifiedFeatures));
        }
        List<String> activeIssues = readKnownIssuesFile(directory.resolve(KNOWN_ISSUES_FILE)).stream()
                .filter(issue -> "open".equals(issue.status()) || "blocked".equals(issue.status()))
                .map(KnownIssue::id)
                .toList();
        if (!activeIssues.isEmpty()) {
            throw new LongRunningTaskStoreException(
                    "Task " + taskId + " cannot be completed: active known issues "
                            + String.join(", ", activeIssues));
        }
    }

    private void requireReadyForExecution(String taskId, Path directory) {
        if (readFeatures(directory.resolve(FEATURE_LIST_FILE)).isEmpty()) {
            throw new LongRunningTaskStoreException(
                    "Task " + taskId + " cannot enter execution: feature list is empty");
        }
    }

    private void updateTaskTimestamp(String taskId, Instant updatedAt) {
        updateTaskMetadata(taskId, metadata -> new LongRunningTaskMetadata(
                metadata.id(),
                metadata.title(),
                metadata.status(),
                metadata.reason(),
                metadata.executionStarted(),
                metadata.createdAt(),
                updatedAt,
                metadata.controlSessionId(),
                metadata.planSummary()));
    }

    private void updateTaskMetadata(String taskId, java.util.function.UnaryOperator<LongRunningTaskMetadata> updater) {
        Path taskFile = taskDirectoryPath(taskId).resolve(TASK_FILE);
        LongRunningTaskMetadata current = loadTask(taskId);
        LongRunningTaskMetadata updated = Objects.requireNonNull(updater.apply(current), "updated");
        try {
            writeJsonAtomically(taskFile, serializeTask(updated));
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException("Failed to update task metadata for " + taskId, exception);
        }
    }

    private LongRunningTaskMetadata writeTaskLifecycle(
            Path directory,
            LongRunningTaskMetadata metadata,
            String status,
            String reason,
            String failureMessage) {
        LongRunningTaskMetadata updated = new LongRunningTaskMetadata(
                metadata.id(),
                metadata.title(),
                mapLifecycleStatus(status),
                mapLifecycleReason(status, reason),
                determineExecutionStarted(metadata, status),
                metadata.createdAt(),
                Instant.now(),
                metadata.controlSessionId(),
                metadata.planSummary());
        try {
            writeJsonAtomically(directory.resolve(TASK_FILE), serializeTask(updated));
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException(failureMessage, exception);
        }
        return updated;
    }

    private static String mapLifecycleStatus(String status) {
        String normalized = requireNonBlank(status, "status").toUpperCase();
        return switch (normalized) {
            case "DRAFT", "RUNNING", "INTERRUPT", "DONE" -> normalized;
            default -> throw new LongRunningTaskStoreException("Unsupported lifecycle status: " + status);
        };
    }

    private static String mapLifecycleReason(String status, String reason) {
        String normalizedStatus = requireNonBlank(status, "status").toUpperCase();
        if ("DONE".equals(normalizedStatus)) {
            return "task_completed";
        }
        if ("INTERRUPT".equals(normalizedStatus)) {
            return reason == null || reason.isBlank() ? "user_interrupted" : reason.strip();
        }
        return reason == null || reason.isBlank() ? null : reason.strip();
    }

    private static Instant determineExecutionStarted(LongRunningTaskMetadata metadata, String status) {
        if (!"RUNNING".equals(mapLifecycleStatus(status))) {
            return metadata.executionStarted();
        }
        return metadata.executionStarted() == null ? Instant.now() : metadata.executionStarted();
    }

    private List<FeatureItem> readFeatures(Path featureFile) {
        try {
            JsonNode root = mapper.readTree(featureFile.toFile());
            if (!root.isArray()) {
                throw new LongRunningTaskStoreException("Feature list file must contain a JSON array");
            }
            List<FeatureItem> features = new ArrayList<>();
            for (JsonNode item : root) {
                features.add(deserializeFeature(item));
            }
            return validateFeatureList(features, true);
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException("Failed to read feature list from " + featureFile, exception);
        }
    }

    private void writeFeatures(Path featureFile, List<FeatureItem> features, String taskId) {
        ArrayNode root = mapper.createArrayNode();
        for (FeatureItem feature : validateFeatureList(features, true)) {
            root.add(serializeFeature(feature));
        }
        try {
            writeJsonAtomically(featureFile, root);
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException("Failed to write feature list for task " + taskId, exception);
        }
    }

    private List<KnownIssue> readKnownIssuesFile(Path issuesFile) {
        try {
            JsonNode root = mapper.readTree(issuesFile.toFile());
            if (!root.isArray()) {
                throw new LongRunningTaskStoreException("Known issues file must contain a JSON array");
            }
            List<KnownIssue> issues = new ArrayList<>();
            for (JsonNode item : root) {
                issues.add(deserializeKnownIssue(item));
            }
            validateKnownIssues(issues);
            return List.copyOf(issues);
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException("Failed to read known issues from " + issuesFile, exception);
        }
    }

    private void writeKnownIssues(Path issuesFile, List<KnownIssue> issues, String taskId) {
        ArrayNode root = mapper.createArrayNode();
        for (KnownIssue issue : validateKnownIssues(issues)) {
            root.add(serializeKnownIssue(issue));
        }
        try {
            writeJsonAtomically(issuesFile, root);
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException("Failed to write known issues for task " + taskId, exception);
        }
    }

    private List<FeatureItem> validateFeatureList(List<FeatureItem> features, boolean allowPassedFeatures) {
        List<FeatureItem> validated = List.copyOf(Objects.requireNonNullElse(features, List.of()));
        Set<String> ids = new LinkedHashSet<>();
        for (FeatureItem feature : validated) {
            Objects.requireNonNull(feature, "feature");
            validateFeatureId(feature.id());
            ensureListItemsPresent(feature.dependsOn(), "feature.dependsOn");
            ensureListItemsPresent(feature.verificationSteps(), "feature.verificationSteps");
            ensureListItemsPresent(feature.verificationEvidence(), "feature.verificationEvidence");
            if (!feature.passes() && !feature.verificationEvidence().isEmpty()) {
                throw new LongRunningTaskStoreException(
                        "Incomplete feature must not include verification evidence: " + feature.id());
            }
            if (feature.passes()
                    && !feature.verificationSteps().isEmpty()
                    && feature.verificationEvidence().isEmpty()) {
                throw new LongRunningTaskStoreException(
                        "Passed feature must include verification evidence: " + feature.id());
            }
            if (!ids.add(feature.id())) {
                throw new LongRunningTaskStoreException("Duplicate feature id: " + feature.id());
            }
            if (!allowPassedFeatures && feature.passes()) {
                throw new LongRunningTaskStoreException(
                        "Initial feature list must not include passed features: " + feature.id());
            }
        }
        return validated;
    }

    private void validateFeatureDependencies(List<FeatureItem> features, boolean allowPassedFeatures) {
        Set<String> ids = features.stream().map(FeatureItem::id).collect(java.util.stream.Collectors.toSet());
        for (FeatureItem feature : features) {
            if (allowPassedFeatures && feature.passes()) {
                continue;
            }
            for (String depId : feature.dependsOn()) {
                if (depId.equals(feature.id())) {
                    throw new LongRunningTaskStoreException(
                            "Feature " + feature.id() + " has a self-dependency");
                }
                if (!ids.contains(depId)) {
                    throw new LongRunningTaskStoreException(
                            "Feature " + feature.id() + " depends on unknown feature " + depId);
                }
            }
        }

        Set<String> visited = new LinkedHashSet<>();
        Set<String> recursionStack = new LinkedHashSet<>();
        for (FeatureItem feature : features) {
            if (allowPassedFeatures && feature.passes()) {
                continue;
            }
            if (hasCycle(feature.id(), features, visited, recursionStack)) {
                throw new LongRunningTaskStoreException("Circular dependency detected in feature list");
            }
        }
    }

    private boolean hasCycle(String featureId, List<FeatureItem> features, Set<String> visited, Set<String> recursionStack) {
        if (recursionStack.contains(featureId)) {
            return true;
        }
        if (visited.contains(featureId)) {
            return false;
        }
        visited.add(featureId);
        recursionStack.add(featureId);
        FeatureItem feature = features.stream()
                .filter(f -> f.id().equals(featureId))
                .findFirst()
                .orElse(null);
        if (feature != null) {
            for (String depId : feature.dependsOn()) {
                if (hasCycle(depId, features, visited, recursionStack)) {
                    return true;
                }
            }
        }
        recursionStack.remove(featureId);
        return false;
    }

    private List<KnownIssue> validateKnownIssues(List<KnownIssue> issues) {
        List<KnownIssue> validated = List.copyOf(Objects.requireNonNullElse(issues, List.of()));
        Set<String> ids = new LinkedHashSet<>();
        for (KnownIssue issue : validated) {
            Objects.requireNonNull(issue, "issue");
            validateKnownIssue(issue);
            if (!ids.add(issue.id())) {
                throw new LongRunningTaskStoreException("Duplicate known issue id: " + issue.id());
            }
        }
        return validated;
    }

    private void validateKnownIssue(KnownIssue issue) {
        validateFeatureId(issue.id());
        ensureListItemsPresent(issue.verificationSteps(), "knownIssue.verificationSteps");
        if (!ALLOWED_ISSUE_STATUSES.contains(issue.status())) {
            throw new LongRunningTaskStoreException("Unsupported issue status: " + issue.status());
        }
        if ("resolved".equals(issue.status()) && issue.resolvedAt() == null) {
            throw new LongRunningTaskStoreException("Resolved issue must include resolvedAt: " + issue.id());
        }
        if (!"resolved".equals(issue.status()) && issue.resolvedAt() != null) {
            throw new LongRunningTaskStoreException("Only resolved issues may include resolvedAt: " + issue.id());
        }
    }

    private void ensureListItemsPresent(List<String> values, String field) {
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new LongRunningTaskStoreException(field + " must not contain blank items");
            }
        }
    }

    private void validateFeatureId(String id) {
        requireNonBlank(id, "id");
    }

    private ObjectNode serializeTask(LongRunningTaskMetadata metadata) {
        if (!ALLOWED_TASK_STATUSES.contains(metadata.status())) {
            throw new LongRunningTaskStoreException("Unsupported task status: " + metadata.status());
        }
        ObjectNode root = mapper.createObjectNode();
        root.put("id", metadata.id());
        root.put("title", metadata.title());
        root.put("status", metadata.status());
        if (metadata.reason() != null) {
            root.put("reason", metadata.reason());
        }
        if (metadata.executionStarted() != null) {
            root.put("executionStarted", metadata.executionStarted().toString());
        }
        root.put("createdAt", metadata.createdAt().toString());
        root.put("updatedAt", metadata.updatedAt().toString());
        if (metadata.controlSessionId() != null) {
            root.put("controlSessionId", metadata.controlSessionId());
        }
        if (metadata.planSummary() != null) {
            root.put("planSummary", metadata.planSummary());
        }
        return root;
    }

    private ObjectNode serializeFeature(FeatureItem feature) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", feature.id());
        root.put("category", feature.category());
        root.put("priority", feature.priority());
        root.put("description", feature.description());
        ArrayNode dependsOn = mapper.createArrayNode();
        feature.dependsOn().forEach(dependsOn::add);
        root.set("depends_on", dependsOn);
        ArrayNode verificationSteps = mapper.createArrayNode();
        feature.verificationSteps().forEach(verificationSteps::add);
        root.set("verification_steps", verificationSteps);
        root.put("passes", feature.passes());
        ArrayNode verificationEvidence = mapper.createArrayNode();
        feature.verificationEvidence().forEach(verificationEvidence::add);
        root.set("verification_evidence", verificationEvidence);
        return root;
    }

    private FeatureItem deserializeFeature(JsonNode root) {
        List<String> dependsOn = arrayText(root.path("depends_on"));
        List<String> verificationSteps = arrayText(root.path("verification_steps"));
        boolean passes = root.path("passes").asBoolean(false);
        List<String> verificationEvidence = arrayText(root.path("verification_evidence"));
        if (passes
                && !root.has("verification_evidence")
                && verificationEvidence.isEmpty()
                && !verificationSteps.isEmpty()) {
            verificationEvidence = verificationSteps;
        }
        return new FeatureItem(
                requiredText(root, "id"),
                requiredText(root, "category"),
                requiredText(root, "priority"),
                requiredText(root, "description"),
                dependsOn,
                verificationSteps,
                passes,
                verificationEvidence);
    }

    private ObjectNode serializeKnownIssue(KnownIssue issue) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", issue.id());
        root.put("description", issue.description());
        root.put("severity", issue.severity());
        root.put("status", issue.status());
        root.put("discovered_in", issue.discoveredIn());
        ArrayNode verificationSteps = mapper.createArrayNode();
        issue.verificationSteps().forEach(verificationSteps::add);
        root.set("verification_steps", verificationSteps);
        root.put("created_at", issue.createdAt().toString());
        if (issue.resolvedAt() != null) {
            root.put("resolved_at", issue.resolvedAt().toString());
        }
        return root;
    }

    private KnownIssue deserializeKnownIssue(JsonNode root) {
        return new KnownIssue(
                requiredText(root, "id"),
                requiredText(root, "description"),
                requiredText(root, "severity"),
                requiredText(root, "status"),
                requiredText(root, "discovered_in"),
                arrayText(root.path("verification_steps")),
                Instant.parse(requiredText(root, "created_at")),
                optionalText(root, "resolved_at").map(Instant::parse).orElse(null));
    }

    private ObjectNode serializeCheckpoint(LongRunningWorkspaceCheckpoint checkpoint) {
        ObjectNode root = mapper.createObjectNode();
        root.put("capturedAt", checkpoint.capturedAt().toString());
        root.put("projectDirectory", checkpoint.projectDirectory().toString());
        root.put("gitRepository", checkpoint.gitRepository());
        if (checkpoint.gitRoot() != null) {
            root.put("gitRoot", checkpoint.gitRoot().toString());
        }
        if (checkpoint.branch() != null) {
            root.put("branch", checkpoint.branch());
        }
        if (checkpoint.head() != null) {
            root.put("head", checkpoint.head());
        }
        root.put("dirty", checkpoint.dirty());
        root.put("statusShort", checkpoint.statusShort());
        return root;
    }

    private LongRunningWorkspaceCheckpoint deserializeCheckpoint(JsonNode root) {
        return new LongRunningWorkspaceCheckpoint(
                Instant.parse(requiredText(root, "capturedAt")),
                Path.of(requiredText(root, "projectDirectory")),
                root.path("gitRepository").asBoolean(false),
                optionalText(root, "gitRoot").map(Path::of).orElse(null),
                optionalText(root, "branch").orElse(null),
                optionalText(root, "head").orElse(null),
                root.path("dirty").asBoolean(false),
                optionalText(root, "statusShort").orElse(""));
    }

    private LongRunningTaskMetadata readTaskMetadata(Path taskFile, String taskId) {
        try {
            JsonNode root = mapper.readTree(taskFile.toFile());
            String id = requiredText(root, "id");
            if (!taskId.equals(id)) {
                throw new LongRunningTaskStoreException("Task id mismatch in task.json: expected " + taskId + " but found " + id);
            }
            return new LongRunningTaskMetadata(
                    id,
                    requiredText(root, "title"),
                    requiredText(root, "status"),
                    optionalText(root, "reason").orElse(null),
                    optionalText(root, "executionStarted").map(Instant::parse).orElse(null),
                    Instant.parse(requiredText(root, "createdAt")),
                    Instant.parse(requiredText(root, "updatedAt")),
                    optionalText(root, "controlSessionId").orElse(optionalText(root, "sessionId").orElse(null)),
                    optionalText(root, "planSummary").orElse(null));
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException("Failed to read task metadata for " + taskId, exception);
        }
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null) {
            throw new LongRunningTaskStoreException(field + " must not be blank");
        }
        String normalized = value.strip();
        if (normalized.isBlank()) {
            throw new LongRunningTaskStoreException(field + " must not be blank");
        }
        return normalized;
    }

    private static Optional<String> optionalText(JsonNode root, String field) {
        if (!root.has(field) || root.path(field).isNull()) {
            return Optional.empty();
        }
        String text = root.path(field).asText();
        return text == null || text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    private static String requiredText(JsonNode root, String field) {
        return optionalText(root, field)
                .orElseThrow(() -> new LongRunningTaskStoreException("Missing required field: " + field));
    }

    private static List<String> arrayText(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            values.add(item.asText());
        }
        return List.copyOf(values);
    }

    private static Object appendLock(Path path) {
        return ProgressAppendLocks.LOCKS.computeIfAbsent(path.toAbsolutePath().normalize(), ignored -> new Object());
    }

    private void writeJsonAtomically(Path target, JsonNode payload) throws IOException {
        AtomicFiles.writeAtomically(
                target,
                tempFile -> mapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), payload));
    }

    private void writeStringAtomically(Path target, String content) throws IOException {
        AtomicFiles.writeAtomically(target, tempFile -> Files.writeString(tempFile, content == null ? "" : content));
    }

    private static void moveDirectoryIntoPlace(Path stagingDirectory, Path targetDirectory) throws IOException {
        try {
            Files.move(stagingDirectory, targetDirectory, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            try {
                Files.move(stagingDirectory, targetDirectory);
            } catch (FileAlreadyExistsException exception) {
                throw taskDirectoryAlreadyExists(targetDirectory, exception);
            }
        } catch (FileAlreadyExistsException exception) {
            throw taskDirectoryAlreadyExists(targetDirectory, exception);
        }
    }

    private static LongRunningTaskStoreException taskDirectoryAlreadyExists(
            Path targetDirectory,
            FileAlreadyExistsException exception) {
        return new LongRunningTaskStoreException(
                "Task directory already exists for " + targetDirectory.getFileName(), exception);
    }

    private void requireRegularFile(Path file, String label, String taskId) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new LongRunningTaskStoreException("Missing " + label + " for task " + taskId);
        }
    }

    private String initScript(LongRunningWorkspaceCheckpoint checkpoint) {
        if (!checkpoint.gitRepository()) {
            return """
                    #!/usr/bin/env bash
                    set -euo pipefail

                    # No git repository detected when the task was created.
                    """;
        }
        return """
                #!/usr/bin/env bash
                set -euo pipefail

                # Restored from checkpoint:
                # branch: %s
                # head: %s
                # dirty: %s
                """.formatted(
                checkpoint.branch() == null ? "" : checkpoint.branch(),
                checkpoint.head() == null ? "" : checkpoint.head(),
                checkpoint.dirty());
    }

    private LongRunningEventLog eventLog() {
        if (eventLog == null) {
            throw new IllegalStateException("eventLog not attached");
        }
        return eventLog;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(current -> {
                        try {
                            Files.deleteIfExists(current);
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    });
        } catch (RuntimeException exception) {
            if (exception.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw exception;
        }
    }

    private static final class ProgressAppendLocks {
        private static final Map<Path, Object> LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

        private ProgressAppendLocks() {
        }
    }
}
