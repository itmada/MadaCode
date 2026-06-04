package madacode.core.session;

import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.model.MessageRole;
import madacode.core.model.MetaEvent;
import madacode.core.model.TokenUsage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.plan.PlanItem;
import madacode.plan.PlanStatus;
import madacode.plan.TodoItem;
import madacode.logging.DiagnosticEventLogger;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Optional;
import java.util.List;
import java.util.Objects;

public final class SessionStorage {

    private static final String DEFAULT_DIRECTORY = ".mada/sessions";

    private final Path rootDirectory;
    private final ObjectMapper mapper;

    public record SessionSummary(
            String sessionId,
            Instant createdAt,
            Path workingDirectory,
            int messageCount,
            Path path,
            Instant lastModifiedAt) implements SessionListEntry {
    }

    public SessionStorage(Path rootDirectory) {
        this(rootDirectory, new ObjectMapper());
    }

    SessionStorage(Path rootDirectory, ObjectMapper mapper) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory").toAbsolutePath().normalize();
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public static SessionStorage defaultStorage() {
        Path home = Path.of(System.getProperty("user.home"));
        return new SessionStorage(home.resolve(DEFAULT_DIRECTORY));
    }

    public Path transcriptPath(String sessionId) {
        return rootDirectory.resolve(SessionIdPolicy.validate(sessionId) + ".json");
    }

    public void save(ConversationSession session) {
        Objects.requireNonNull(session, "session");
        Path target = transcriptPath(session.sessionId());
        Path tempFile = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(rootDirectory);
            mapper.writerWithDefaultPrettyPrinter().writeValue(tempFile.toFile(), serializeSession(session));
            moveIntoPlace(tempFile, target);
            DiagnosticEventLogger.transcriptSaved(session, target);
        } catch (IOException exception) {
            throw new SessionStorageException("Failed to save transcript for session " + session.sessionId(), exception);
        }
    }

    public ConversationSession load(String sessionId) {
        Path transcriptPath = transcriptPath(sessionId);
        try {
            if (!Files.isRegularFile(transcriptPath)) {
                throw new SessionStorageException("Transcript not found for session " + sessionId);
            }
            JsonNode root = mapper.readTree(transcriptPath.toFile());
            ConversationSession session = deserializeSession(root);
            DiagnosticEventLogger.transcriptLoaded(session, transcriptPath);
            return session;
        } catch (IOException exception) {
            throw new SessionStorageException("Failed to load transcript for session " + sessionId, exception);
        }
    }

    public List<SessionSummary> listSessions() {
        if (!Files.isDirectory(rootDirectory)) {
            return List.of();
        }

        List<SessionSummary> summaries = new ArrayList<>();
        try (var paths = Files.newDirectoryStream(rootDirectory, "*.json")) {
            for (Path path : paths) {
                readSummary(path).ifPresent(summaries::add);
            }
        } catch (IOException exception) {
            throw new SessionStorageException("Failed to list transcripts in " + rootDirectory, exception);
        }

        summaries.sort(Comparator.comparing(SessionSummary::lastModifiedAt).reversed());
        return List.copyOf(summaries);
    }

    /**
     * Returns all entries on disk — both valid summaries and corrupted files.
     * Callers that need to show the user what exists (e.g. {@code /sessions})
     * should use this; callers that need to resume a session should use
     * {@link #listSessions()} which filters to valid-only.
     */
    public List<SessionListEntry> listEntries() {
        if (!Files.isDirectory(rootDirectory)) {
            return List.of();
        }

        List<SessionListEntry> entries = new ArrayList<>();
        try (var paths = Files.newDirectoryStream(rootDirectory, "*.json")) {
            for (Path path : paths) {
                entries.add(readEntry(path));
            }
        } catch (IOException exception) {
            throw new SessionStorageException("Failed to list transcripts in " + rootDirectory, exception);
        }

        entries.sort(Comparator.comparing(SessionListEntry::lastModifiedAt).reversed());
        return List.copyOf(entries);
    }

    public void delete(String sessionId) {
        Path target = transcriptPath(sessionId);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new SessionStorageException("Failed to delete session " + sessionId, e);
        }
    }

    public Optional<ConversationSession> loadIfExists(String sessionId) {
        Path p = transcriptPath(sessionId);
        if (!Files.isRegularFile(p)) {
            return Optional.empty();
        }
        return Optional.of(load(sessionId));
    }

    public Optional<SessionSummary> findMostRecent() {
        return listSessions().stream().findFirst();
    }

    private ObjectNode serializeSession(ConversationSession session) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", SchemaMigrator.CURRENT);
        root.put("sessionId", session.sessionId());
        root.put("createdAt", session.createdAt().toString());
        root.put("workingDirectory", session.workingDirectory().toString());
        root.put("workflowMode", session.workflowMode().id());
        root.put("planMode", session.isPlanMode());
        root.put("permissionMode", session.permissionMode().id());
        if (session.longRunningStage() != null) {
            root.put("longRunningStage", session.longRunningStage().name());
        }
        if (session.longRunningTaskId() != null) {
            root.put("longRunningTaskId", session.longRunningTaskId());
        }
        if (session.longRunningTaskDirectory() != null) {
            root.put("longRunningTaskDirectory", session.longRunningTaskDirectory());
        }
        if (session.longRunningTaskTitle() != null) {
            root.put("longRunningTaskTitle", session.longRunningTaskTitle());
        }
        if (session.longRunningReason() != null) {
            root.put("longRunningReason", session.longRunningReason());
        }
        if (session.longRunningPlanSummary() != null) {
            root.put("longRunningPlanSummary", session.longRunningPlanSummary());
        }
        if (session.isLongRunningWorkerSession()) {
            root.put("longRunningWorkerSession", true);
        }
        session.pendingLongRunningTransitionRequest()
                .ifPresent(request -> root.set("pendingLongRunningTransitionRequest",
                        serializeTransitionRequest(request)));
        ArrayNode messages = mapper.createArrayNode();
        for (Message message : session.messages()) {
            messages.add(serializeMessage(message));
        }
        root.set("messages", messages);

        ArrayNode tasksNode = mapper.createArrayNode();
        for (PlanItem task : session.plan().items()) {
            tasksNode.add(serializeTask(task));
        }
        root.set("tasks", tasksNode);

        ArrayNode todosNode = mapper.createArrayNode();
        for (TodoItem todo : session.plan().todos()) {
            ObjectNode todoNode = mapper.createObjectNode();
            todoNode.put("content", todo.content());
            todoNode.put("status", todo.status());
            todosNode.add(todoNode);
        }
        root.set("todos", todosNode);

        ArrayNode historyNode = mapper.createArrayNode();
        for (String input : session.inputHistory()) {
            historyNode.add(input);
        }
        root.set("history", historyNode);

        return root;
    }

    private ObjectNode serializeTask(PlanItem item) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", item.id());
        node.put("title", item.title());
        node.put("description", item.description());
        node.put("status", item.status().name());
        ArrayNode blockedBy = mapper.createArrayNode();
        for (String dep : item.blockedBy()) {
            blockedBy.add(dep);
        }
        node.set("blockedBy", blockedBy);
        node.put("createdAt", item.createdAt().toString());
        node.put("updatedAt", item.updatedAt().toString());
        if (!item.activeForm().isEmpty()) {
            node.put("activeForm", item.activeForm());
        }
        return node;
    }

    private ObjectNode serializeTransitionRequest(LongRunningTransitionRequest request) {
        ObjectNode node = mapper.createObjectNode();
        node.put("sourceStage", request.sourceStage().name());
        node.put("targetStage", request.targetStage().name());
        node.put("reason", request.reason());
        if (request.summary() != null) {
            node.put("summary", request.summary());
        }
        if (request.planDelta() != null) {
            node.put("planDelta", request.planDelta());
        }
        node.put("requestedAt", request.requestedAt().toString());
        if (request.requestedBy() != null) {
            node.put("requestedBy", request.requestedBy());
        }
        node.put("userConfirmationRequired", request.userConfirmationRequired());
        return node;
    }

    private void moveIntoPlace(Path tempFile, Path target) throws IOException {
        try {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(tempFile, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private ObjectNode serializeMessage(Message message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", message.role().name());

        ArrayNode contentBlocks = mapper.createArrayNode();
        for (ContentBlock block : message.contentBlocks()) {
            contentBlocks.add(serializeContentBlock(block));
        }
        node.set("contentBlocks", contentBlocks);
        return node;
    }

    private ObjectNode serializeContentBlock(ContentBlock block) {
        return switch (block) {
            case ContentBlock.TextBlock textBlock -> {
                ObjectNode node = mapper.createObjectNode();
                node.put("type", "text");
                node.put("text", textBlock.text());
                yield node;
            }
            case ContentBlock.ThinkingBlock thinkingBlock -> {
                ObjectNode node = mapper.createObjectNode();
                node.put("type", "thinking");
                node.put("thinking", thinkingBlock.thinking());
                yield node;
            }
            case ContentBlock.ToolUseBlock toolUseBlock -> {
                ObjectNode node = mapper.createObjectNode();
                node.put("type", "tool_use");
                node.put("id", toolUseBlock.id());
                node.put("name", toolUseBlock.name());
                node.set("input", toolUseBlock.input());
                yield node;
            }
            case ContentBlock.ToolResultBlock toolResultBlock -> {
                ObjectNode node = mapper.createObjectNode();
                node.put("type", "tool_result");
                node.put("toolUseId", toolResultBlock.toolUseId());
                node.put("content", toolResultBlock.content());
                node.put("success", toolResultBlock.success());
                node.put("durationMs", toolResultBlock.durationMs());
                yield node;
            }
        };
    }

    private ConversationSession deserializeSession(JsonNode root) {
        int schemaVersion = optionalSchemaVersion(root);
        if (schemaVersion > SchemaMigrator.CURRENT) {
            throw new SessionStorageException("Unsupported transcript schemaVersion: " + schemaVersion);
        }

        // 升级到最新 schema，之后只处理一种格式
        ObjectNode migrated = SchemaMigrator.migrateToLatest((ObjectNode) root);

        String sessionId = requiredText(migrated, "sessionId");
        Instant createdAt = Instant.parse(requiredText(migrated, "createdAt"));
        Path workingDirectory = Path.of(requiredText(migrated, "workingDirectory"));

        JsonNode messagesNode = migrated.path("messages");
        if (!messagesNode.isArray()) {
            throw new SessionStorageException("Transcript messages must be an array");
        }

        List<Message> messages = new ArrayList<>();
        for (JsonNode messageNode : messagesNode) {
            messages.add(deserializeMessage(messageNode));
        }

        List<PlanItem> tasks = new ArrayList<>();
        JsonNode tasksNode = migrated.path("tasks");
        if (tasksNode.isArray()) {
            for (JsonNode taskNode : tasksNode) {
                tasks.add(deserializeTask(taskNode));
            }
        }

        List<TodoItem> todos = new ArrayList<>();
        JsonNode todosNode = migrated.path("todos");
        if (todosNode.isArray()) {
            for (JsonNode todoNode : todosNode) {
                todos.add(new TodoItem(
                        todoNode.path("content").asText(""),
                        todoNode.path("status").asText("pending")));
            }
        }

        List<String> history = new ArrayList<>();
        JsonNode historyNode = migrated.path("history");
        if (historyNode.isArray()) {
            for (JsonNode entry : historyNode) {
                history.add(entry.asText());
            }
        }

        ConversationSession session = new ConversationSession(
                sessionId, createdAt, workingDirectory, messages, tasks, todos, history);
        SessionMode workflowMode = readWorkflowMode(migrated);
        session.setWorkflowMode(workflowMode);
        session.setPlanMode(migrated.path("planMode").asBoolean(false));
        session.setPermissionMode(madacode.permission.PermissionMode.parse(
                migrated.path("permissionMode").asText(null)).orElse(madacode.permission.PermissionMode.DEFAULT));
        if (workflowMode == SessionMode.LONG_RUNNING) {
            session.setLongRunningStage(readLongRunningStage(migrated, workflowMode));
            session.setLongRunningTaskId(optionalText(migrated, "longRunningTaskId"));
            session.setLongRunningTaskDirectory(optionalText(migrated, "longRunningTaskDirectory"));
            session.setLongRunningTaskTitle(optionalText(migrated, "longRunningTaskTitle"));
            session.setLongRunningReason(optionalText(migrated, "longRunningReason"));
            session.setLongRunningPlanSummary(optionalText(migrated, "longRunningPlanSummary"));
            session.setLongRunningWorkerSession(migrated.path("longRunningWorkerSession").asBoolean(false));
            JsonNode pendingRequest = migrated.get("pendingLongRunningTransitionRequest");
            if (pendingRequest != null && !pendingRequest.isNull()) {
                session.setPendingLongRunningTransitionRequest(deserializeTransitionRequest(pendingRequest));
            }
        }
        return session;
    }

    private SessionMode readWorkflowMode(JsonNode node) {
        String raw = optionalText(node, "workflowMode");
        if (raw == null) {
            return SessionMode.COMMON;
        }
        return SessionMode.parse(raw)
                .orElseThrow(() -> new SessionStorageException("Unsupported workflowMode: " + raw));
    }

    private LongRunningStage readLongRunningStage(JsonNode node, SessionMode workflowMode) {
        String raw = optionalText(node, "longRunningStage");
        if (raw == null) {
            return null;
        }
        if (workflowMode != SessionMode.LONG_RUNNING) {
            return null;
        }
        return LongRunningStage.fromWire(raw)
                .orElseThrow(() -> new SessionStorageException("Unsupported longRunningStage: " + raw));
    }

    private LongRunningTransitionRequest deserializeTransitionRequest(JsonNode node) {
        if (!node.isObject()) {
            throw new SessionStorageException("pendingLongRunningTransitionRequest must be an object");
        }
        String sourceRaw = requiredText(node, "sourceStage");
        String targetRaw = requiredText(node, "targetStage");
        LongRunningStage source = LongRunningStage.fromWire(sourceRaw)
                .orElseThrow(() -> new SessionStorageException("Unsupported sourceStage: " + sourceRaw));
        LongRunningStage target = LongRunningStage.fromWire(targetRaw)
                .orElseThrow(() -> new SessionStorageException("Unsupported targetStage: " + targetRaw));
        return new LongRunningTransitionRequest(
                source,
                target,
                requiredText(node, "reason"),
                optionalText(node, "summary"),
                optionalText(node, "planDelta"),
                node.has("requestedAt") ? Instant.parse(node.path("requestedAt").asText()) : Instant.now(),
                optionalText(node, "requestedBy"),
                node.path("userConfirmationRequired").asBoolean(true));
    }

    private PlanItem deserializeTask(JsonNode node) {
        List<String> blockedBy = new ArrayList<>();
        JsonNode blockedByNode = node.path("blockedBy");
        if (blockedByNode.isArray()) {
            for (JsonNode dep : blockedByNode) {
                blockedBy.add(dep.asText());
            }
        }

        PlanStatus status;
        try {
            status = PlanStatus.valueOf(requiredText(node, "status"));
        } catch (IllegalArgumentException e) {
            status = PlanStatus.COMPLETED;
        }

        return new PlanItem(
                requiredText(node, "id"),
                requiredText(node, "title"),
                node.path("description").asText(""),
                status,
                blockedBy,
                node.has("createdAt") ? Instant.parse(node.path("createdAt").asText()) : Instant.now(),
                node.has("updatedAt") ? Instant.parse(node.path("updatedAt").asText()) : Instant.now(),
                node.has("activeForm") ? node.path("activeForm").asText() : "");
    }

    private SessionListEntry readEntry(Path path) {
        try {
            JsonNode root = mapper.readTree(path.toFile());
            int schemaVersion = optionalSchemaVersion(root);
            if (schemaVersion > SchemaMigrator.CURRENT) {
                return new SessionListEntry.Corrupted(
                        path.toAbsolutePath().normalize(),
                        Files.getLastModifiedTime(path).toInstant(),
                        "unsupported schemaVersion " + schemaVersion);
            }
            JsonNode messagesNode = root.path("messages");
            if (!messagesNode.isArray()) {
                return new SessionListEntry.Corrupted(
                        path.toAbsolutePath().normalize(),
                        Files.getLastModifiedTime(path).toInstant(),
                        "missing or invalid messages array");
            }
            return new SessionSummary(
                    requiredText(root, "sessionId"),
                    Instant.parse(requiredText(root, "createdAt")),
                    Path.of(requiredText(root, "workingDirectory")),
                    messagesNode.size(),
                    path.toAbsolutePath().normalize(),
                    Files.getLastModifiedTime(path).toInstant());
        } catch (IOException | RuntimeException exception) {
            Instant mtime;
            try {
                mtime = Files.getLastModifiedTime(path).toInstant();
            } catch (IOException ignored) {
                mtime = Instant.EPOCH;
            }
            return new SessionListEntry.Corrupted(
                    path.toAbsolutePath().normalize(),
                    mtime,
                    exception.getMessage() != null ? exception.getMessage() : exception.getClass().getSimpleName());
        }
    }

    private java.util.Optional<SessionSummary> readSummary(Path path) {
        try {
            JsonNode root = mapper.readTree(path.toFile());
            int schemaVersion = optionalSchemaVersion(root);
            if (schemaVersion > SchemaMigrator.CURRENT) {
                return java.util.Optional.empty();
            }
            JsonNode messagesNode = root.path("messages");
            if (!messagesNode.isArray()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new SessionSummary(
                    requiredText(root, "sessionId"),
                    Instant.parse(requiredText(root, "createdAt")),
                    Path.of(requiredText(root, "workingDirectory")),
                    messagesNode.size(),
                    path.toAbsolutePath().normalize(),
                    Files.getLastModifiedTime(path).toInstant()));
        } catch (IOException | RuntimeException exception) {
            return java.util.Optional.empty();
        }
    }

    private int optionalSchemaVersion(JsonNode root) {
        JsonNode value = root.get("schemaVersion");
        if (value == null || value.isNull()) {
            return 1;
        }
        if (!value.canConvertToInt()) {
            throw new SessionStorageException("Transcript schemaVersion must be an integer");
        }
        int schemaVersion = value.asInt();
        if (schemaVersion < 1) {
            throw new SessionStorageException("Transcript schemaVersion must be greater than 0");
        }
        return schemaVersion;
    }

    private Message deserializeMessage(JsonNode messageNode) {
        MessageRole role = MessageRole.valueOf(requiredText(messageNode, "role"));
        JsonNode contentBlocksNode = messageNode.path("contentBlocks");
        if (!contentBlocksNode.isArray()) {
            throw new SessionStorageException("Transcript contentBlocks must be an array");
        }

        List<ContentBlock> contentBlocks = new ArrayList<>();
        for (JsonNode blockNode : contentBlocksNode) {
            contentBlocks.add(deserializeContentBlock(blockNode));
        }

        return switch (role) {
            case SYSTEM -> Message.system(contentFrom(contentBlocks));
            case USER -> messageForRole(role, contentBlocks);
            case ASSISTANT -> messageForRole(role, contentBlocks);
        };
    }

    private Message messageForRole(MessageRole role, List<ContentBlock> contentBlocks) {
        if (contentBlocks.size() == 1 && contentBlocks.getFirst() instanceof ContentBlock.TextBlock textBlock) {
            return role == MessageRole.USER
                    ? Message.user(textBlock.text())
                    : Message.assistant(textBlock.text());
        }
        return role == MessageRole.USER
                ? Message.user(contentBlocks)
                : Message.assistant(contentBlocks);
    }

    private ContentBlock deserializeContentBlock(JsonNode blockNode) {
        String type = requiredText(blockNode, "type");
        return switch (type) {
            case "text" -> new ContentBlock.TextBlock(requiredText(blockNode, "text"));
            case "thinking" -> new ContentBlock.ThinkingBlock(requiredText(blockNode, "thinking"));
            case "tool_use" -> new ContentBlock.ToolUseBlock(
                    requiredText(blockNode, "id"),
                    requiredText(blockNode, "name"),
                    requiredObject(blockNode, "input"));
            case "tool_result" -> new ContentBlock.ToolResultBlock(
                    requiredText(blockNode, "toolUseId"),
                    requiredText(blockNode, "content"),
                    blockNode.path("success").asBoolean(true),
                    blockNode.path("durationMs").asLong(-1));
            default -> throw new SessionStorageException("Unsupported content block type: " + type);
        };
    }

    private String contentFrom(List<ContentBlock> contentBlocks) {
        if (contentBlocks.size() != 1 || !(contentBlocks.getFirst() instanceof ContentBlock.TextBlock textBlock)) {
            throw new SessionStorageException("System messages must contain exactly one text block");
        }
        return textBlock.text();
    }

    private String requiredText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isTextual()) {
            throw new SessionStorageException("Missing text field: " + fieldName);
        }
        return value.asText();
    }

    private String optionalText(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new SessionStorageException("Field must be text: " + fieldName);
        }
        return value.asText();
    }

    private ObjectNode requiredObject(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value == null || !value.isObject()) {
            throw new SessionStorageException("Missing object field: " + fieldName);
        }
        return ((ObjectNode) value).deepCopy();
    }
}
