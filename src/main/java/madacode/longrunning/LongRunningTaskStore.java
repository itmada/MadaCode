package madacode.longrunning;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class LongRunningTaskStore {

    private static final Pattern SAFE_TASK_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");
    private static final Set<String> ALLOWED_ISSUE_STATUSES = Set.of("open", "resolved", "blocked");
    private static final String TASK_FILE = "task.json";
    private static final String FEATURE_LIST_FILE = "feature_list.json";
    private static final String PROGRESS_FILE = "progress.txt";
    private static final String KNOWN_ISSUES_FILE = "known-issues.json";
    private static final String INIT_SCRIPT_FILE = "init.sh";
    private static final String LOGS_DIR = "logs";
    private static final String ROOT_DIR = ".mada/long-running";
    private static final String DEFAULT_INIT_SCRIPT = """
            #!/usr/bin/env bash
            set -euo pipefail

            # Initialization hook for this long-running task.
            """;

    private final Path projectDirectory;
    private final Path rootDirectory;
    private final ObjectMapper mapper;

    public LongRunningTaskStore(Path projectDirectory) {
        this(projectDirectory, new ObjectMapper());
    }

    LongRunningTaskStore(Path projectDirectory, ObjectMapper mapper) {
        this.projectDirectory = Objects.requireNonNull(projectDirectory, "projectDirectory")
                .toAbsolutePath()
                .normalize();
        this.rootDirectory = this.projectDirectory.resolve(ROOT_DIR).normalize();
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public static LongRunningTaskMetadata createTask(Path projectDirectory, CreateTaskRequest request) {
        return new LongRunningTaskStore(projectDirectory).createTask(request);
    }

    public synchronized LongRunningTaskMetadata createTask(CreateTaskRequest request) {
        Objects.requireNonNull(request, "request");
        String taskId = validateTaskId(request.id());
        Path taskDirectory = taskDirectory(taskId);
        if (Files.exists(taskDirectory)) {
            throw new LongRunningTaskStoreException("Task directory already exists for " + taskId);
        }

        Instant now = Instant.now();
        LongRunningTaskMetadata metadata = new LongRunningTaskMetadata(
                taskId,
                request.title(),
                request.status(),
                now,
                now,
                request.sessionId(),
                request.stage());

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
                writeStringAtomically(stagingDirectory.resolve(INIT_SCRIPT_FILE), DEFAULT_INIT_SCRIPT);
                moveIntoPlace(stagingDirectory, taskDirectory);
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

    public synchronized LongRunningTaskMetadata loadTask(String taskId) {
        Path directory = validateTaskDirectory(taskId);
        Path taskFile = directory.resolve(TASK_FILE);
        return readTaskMetadata(taskFile, validateTaskId(taskId));
    }

    public synchronized List<FeatureItem> readFeatureList(String taskId) {
        Path directory = validateTaskDirectory(taskId);
        return readFeatures(directory.resolve(FEATURE_LIST_FILE));
    }

    public synchronized void writeInitialFeatureList(String taskId, List<FeatureItem> features) {
        Path directory = validateTaskDirectory(taskId);
        List<FeatureItem> existing = readFeatures(directory.resolve(FEATURE_LIST_FILE));
        if (!existing.isEmpty()) {
            throw new LongRunningTaskStoreException("Initial feature list already exists for " + taskId);
        }
        List<FeatureItem> validated = validateFeatureList(features, false);
        writeFeatures(directory.resolve(FEATURE_LIST_FILE), validated, taskId);
        updateTaskTimestamp(taskId, Instant.now());
    }

    public synchronized FeatureItem markFeaturePassed(String taskId, String featureId) {
        requireNonBlank(featureId, "featureId");
        Path directory = validateTaskDirectory(taskId);
        List<FeatureItem> features = readFeatures(directory.resolve(FEATURE_LIST_FILE));
        List<FeatureItem> updated = new ArrayList<>(features.size());
        FeatureItem changed = null;
        for (FeatureItem feature : features) {
            if (feature.id().equals(featureId)) {
                changed = feature.passes()
                        ? feature
                        : new FeatureItem(
                                feature.id(),
                                feature.category(),
                                feature.priority(),
                                feature.description(),
                                feature.dependsOn(),
                                feature.verificationSteps(),
                                true);
                updated.add(changed);
            } else {
                updated.add(feature);
            }
        }
        if (changed == null) {
            throw new LongRunningTaskStoreException("Unknown feature id " + featureId + " for task " + taskId);
        }
        writeFeatures(directory.resolve(FEATURE_LIST_FILE), updated, taskId);
        updateTaskTimestamp(taskId, Instant.now());
        return changed;
    }

    public synchronized List<KnownIssue> readKnownIssues(String taskId) {
        Path directory = validateTaskDirectory(taskId);
        return readKnownIssuesFile(directory.resolve(KNOWN_ISSUES_FILE));
    }

    public synchronized KnownIssue recordIssue(String taskId, KnownIssue issue) {
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

    public synchronized KnownIssue markIssueResolved(String taskId, String issueId) {
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

    public synchronized void appendProgress(String taskId, String text) {
        requireNonBlank(text, "text");
        Path directory = validateTaskDirectory(taskId);
        Path progressFile = directory.resolve(PROGRESS_FILE);
        try {
            Files.writeString(progressFile, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            updateTaskTimestamp(taskId, Instant.now());
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException("Failed to append progress for task " + taskId, exception);
        }
    }

    public synchronized Path validateTaskDirectory(String taskId) {
        String safeTaskId = validateTaskId(taskId);
        Path directory = taskDirectory(safeTaskId);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new LongRunningTaskStoreException("Task directory not found for " + safeTaskId);
        }
        requireRegularFile(directory.resolve(TASK_FILE), TASK_FILE, safeTaskId);
        requireRegularFile(directory.resolve(FEATURE_LIST_FILE), FEATURE_LIST_FILE, safeTaskId);
        requireRegularFile(directory.resolve(PROGRESS_FILE), PROGRESS_FILE, safeTaskId);
        requireRegularFile(directory.resolve(KNOWN_ISSUES_FILE), KNOWN_ISSUES_FILE, safeTaskId);
        requireRegularFile(directory.resolve(INIT_SCRIPT_FILE), INIT_SCRIPT_FILE, safeTaskId);
        Path logs = directory.resolve(LOGS_DIR);
        if (!Files.isDirectory(logs, LinkOption.NOFOLLOW_LINKS)) {
            throw new LongRunningTaskStoreException("Missing logs directory for task " + safeTaskId);
        }
        readTaskMetadata(directory.resolve(TASK_FILE), safeTaskId);
        readFeatures(directory.resolve(FEATURE_LIST_FILE));
        readKnownIssuesFile(directory.resolve(KNOWN_ISSUES_FILE));
        return directory;
    }

    private void updateTaskTimestamp(String taskId, Instant updatedAt) {
        LongRunningTaskMetadata metadata = loadTask(taskId);
        LongRunningTaskMetadata updated = new LongRunningTaskMetadata(
                metadata.id(),
                metadata.title(),
                metadata.status(),
                metadata.createdAt(),
                updatedAt,
                metadata.sessionId(),
                metadata.stage());
        Path taskFile = taskDirectory(taskId).resolve(TASK_FILE);
        try {
            writeJsonAtomically(taskFile, serializeTask(updated));
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException("Failed to update task metadata for " + taskId, exception);
        }
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
        ObjectNode root = mapper.createObjectNode();
        root.put("id", metadata.id());
        root.put("title", metadata.title());
        root.put("status", metadata.status());
        root.put("createdAt", metadata.createdAt().toString());
        root.put("updatedAt", metadata.updatedAt().toString());
        root.put("sessionId", metadata.sessionId());
        root.put("stage", metadata.stage());
        return root;
    }

    private LongRunningTaskMetadata deserializeTask(JsonNode root) {
        return new LongRunningTaskMetadata(
                requiredText(root, "id"),
                requiredText(root, "title"),
                requiredText(root, "status"),
                Instant.parse(requiredText(root, "createdAt")),
                Instant.parse(requiredText(root, "updatedAt")),
                requiredText(root, "sessionId"),
                requiredText(root, "stage"));
    }

    private LongRunningTaskMetadata readTaskMetadata(Path taskFile, String expectedTaskId) {
        try {
            JsonNode root = mapper.readTree(taskFile.toFile());
            LongRunningTaskMetadata metadata = deserializeTask(root);
            if (!metadata.id().equals(expectedTaskId)) {
                throw new LongRunningTaskStoreException("Task metadata id mismatch for " + expectedTaskId);
            }
            return metadata;
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException(
                    "Failed to load task metadata for " + expectedTaskId, exception);
        }
    }

    private ObjectNode serializeFeature(FeatureItem feature) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", feature.id());
        root.put("category", feature.category());
        root.put("priority", feature.priority());
        root.put("description", feature.description());
        root.set("depends_on", stringsArray(feature.dependsOn()));
        root.set("verification_steps", stringsArray(feature.verificationSteps()));
        root.put("passes", feature.passes());
        return root;
    }

    private FeatureItem deserializeFeature(JsonNode root) {
        return new FeatureItem(
                requiredText(root, "id"),
                requiredText(root, "category"),
                requiredText(root, "priority"),
                requiredText(root, "description"),
                readStringArray(root, "depends_on"),
                readStringArray(root, "verification_steps"),
                root.path("passes").asBoolean(false));
    }

    private ObjectNode serializeKnownIssue(KnownIssue issue) {
        ObjectNode root = mapper.createObjectNode();
        root.put("id", issue.id());
        root.put("description", issue.description());
        root.put("severity", issue.severity());
        root.put("status", issue.status());
        root.put("discoveredIn", issue.discoveredIn());
        root.set("verification_steps", stringsArray(issue.verificationSteps()));
        root.put("createdAt", issue.createdAt().toString());
        if (issue.resolvedAt() != null) {
            root.put("resolvedAt", issue.resolvedAt().toString());
        }
        return root;
    }

    private KnownIssue deserializeKnownIssue(JsonNode root) {
        return new KnownIssue(
                requiredText(root, "id"),
                requiredText(root, "description"),
                requiredText(root, "severity"),
                requiredText(root, "status"),
                requiredText(root, "discoveredIn"),
                readStringArray(root, "verification_steps"),
                Instant.parse(requiredText(root, "createdAt")),
                optionalText(root, "resolvedAt").map(Instant::parse).orElse(null));
    }

    private ArrayNode stringsArray(List<String> values) {
        ArrayNode array = mapper.createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private List<String> readStringArray(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node.isMissingNode()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new LongRunningTaskStoreException(field + " must be a JSON array");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new LongRunningTaskStoreException(field + " must contain only strings");
            }
            values.add(item.asText());
        }
        return List.copyOf(values);
    }

    private String requiredText(JsonNode root, String field) {
        return optionalText(root, field)
                .orElseThrow(() -> new LongRunningTaskStoreException("Missing required field: " + field));
    }

    private Optional<String> optionalText(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (!node.isTextual()) {
            return Optional.empty();
        }
        String text = node.asText();
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    private void requireRegularFile(Path path, String label, String taskId) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new LongRunningTaskStoreException("Missing " + label + " for task " + taskId);
        }
    }

    private Path taskDirectory(String taskId) {
        Path directory = rootDirectory.resolve(validateTaskId(taskId)).normalize();
        if (!directory.startsWith(rootDirectory)) {
            throw new LongRunningTaskStoreException("Task directory escapes project root: " + taskId);
        }
        return directory;
    }

    private String validateTaskId(String taskId) {
        requireNonBlank(taskId, "taskId");
        if (taskId.contains("/") || taskId.contains("\\") || taskId.contains("..")) {
            throw new IllegalArgumentException("taskId contains forbidden path characters");
        }
        if (!SAFE_TASK_ID.matcher(taskId).matches()) {
            throw new IllegalArgumentException("taskId must be a safe token");
        }
        return taskId;
    }

    private String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private void writeJsonAtomically(Path target, JsonNode node) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Path tempFile = null;
        boolean moved = false;
        try {
            Files.createDirectories(parent);
            tempFile = Files.createTempFile(parent, target.getFileName().toString() + ".", ".tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), node);
            moveIntoPlace(tempFile, target);
            moved = true;
        } finally {
            if (!moved && tempFile != null) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    private void writeStringAtomically(Path target, String content) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Path tempFile = null;
        boolean moved = false;
        try {
            Files.createDirectories(parent);
            tempFile = Files.createTempFile(parent, target.getFileName().toString() + ".", ".tmp");
            Files.writeString(tempFile, content == null ? "" : content);
            moveIntoPlace(tempFile, target);
            moved = true;
        } finally {
            if (!moved && tempFile != null) {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    private void moveIntoPlace(Path tempFile, Path target) throws IOException {
        try {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted((left, right) -> right.getNameCount() - left.getNameCount())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException exception) {
                            throw new LongRunningTaskStoreException(
                                    "Failed to clean up staging directory " + root, exception);
                        }
                    });
        }
    }
}
