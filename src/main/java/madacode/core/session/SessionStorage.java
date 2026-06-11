package madacode.core.session;

import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.model.MessageKind;
import madacode.core.model.MessageRole;
import madacode.logging.DefaultDiagnosticEvents;
import madacode.logging.DiagnosticEvents;
import madacode.permission.PermissionMode;
import madacode.plan.PlanItem;
import madacode.plan.PlanStatus;
import madacode.plan.TodoItem;
import madacode.util.AtomicFiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class SessionStorage {

    private static final String FORMAT_JSONL = "madacode-session-jsonl";
    private static final String HEADER_KIND = "header";
    private static final String MESSAGE_KIND = "message";

    private final Path rootDirectory;
    private final ObjectMapper mapper;
    private final DiagnosticEvents diagnosticEvents;

    public record SessionSummary(
            String sessionId,
            Instant createdAt,
            Path workingDirectory,
            int messageCount,
            Path path,
            Instant lastModifiedAt) implements SessionListEntry {
    }

    public SessionStorage(Path rootDirectory) {
        this(rootDirectory, new DefaultDiagnosticEvents());
    }

    public SessionStorage(Path rootDirectory, DiagnosticEvents diagnosticEvents) {
        this(rootDirectory, new ObjectMapper(), diagnosticEvents);
    }

    SessionStorage(Path rootDirectory, ObjectMapper mapper) {
        this(rootDirectory, mapper, new DefaultDiagnosticEvents());
    }

    SessionStorage(Path rootDirectory, ObjectMapper mapper, DiagnosticEvents diagnosticEvents) {
        this.rootDirectory = Objects.requireNonNull(rootDirectory, "rootDirectory").toAbsolutePath().normalize();
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.diagnosticEvents = Objects.requireNonNull(diagnosticEvents, "diagnosticEvents");
    }

    public Path transcriptPath(String sessionId) {
        return jsonlPath(sessionId);
    }

    public void save(ConversationSession session) {
        Objects.requireNonNull(session, "session");
        try {
            Files.createDirectories(rootDirectory);
            saveTranscript(session);
            writeStateFile(session);
            Path legacyPath = legacyJsonPath(session.sessionId());
            if (Files.isRegularFile(legacyPath)) {
                Files.move(legacyPath, legacyJsonBakPath(session.sessionId()), StandardCopyOption.REPLACE_EXISTING);
            }
            diagnosticEvents.transcriptSaved(session, transcriptPath(session.sessionId()));
        } catch (IOException exception) {
            throw new SessionStorageException(
                    "Failed to save transcript for session " + session.sessionId(), exception);
        }
    }

    public ConversationSession load(String sessionId) {
        try {
            ConversationSession session = loadInternal(sessionId)
                    .orElseThrow(() -> new SessionStorageException("Transcript not found for session " + sessionId));
            diagnosticEvents.transcriptLoaded(session, transcriptPath(sessionId));
            return session;
        } catch (IOException exception) {
            throw new SessionStorageException("Failed to load transcript for session " + sessionId, exception);
        }
    }

    public List<SessionSummary> listSessions() {
        return listEntries().stream()
                .filter(SessionSummary.class::isInstance)
                .map(SessionSummary.class::cast)
                .toList();
    }

    public List<SessionListEntry> listEntries() {
        if (!Files.isDirectory(rootDirectory)) {
            return List.of();
        }

        List<SessionListEntry> entries = new ArrayList<>();
        Map<String, SessionListEntry> dedup = new HashMap<>();
        try (var paths = Files.list(rootDirectory)) {
            for (Path path : paths.toList()) {
                Optional<SessionListEntry> entry = readEntryIfRelevant(path);
                if (entry.isEmpty()) {
                    continue;
                }
                SessionListEntry value = entry.get();
                String key = dedupKey(value);
                SessionListEntry existing = dedup.get(key);
                if (existing == null || prefers(value, existing)) {
                    dedup.put(key, value);
                }
            }
        } catch (IOException exception) {
            throw new SessionStorageException("Failed to list transcripts in " + rootDirectory, exception);
        }

        entries.addAll(dedup.values());
        entries.sort(Comparator.comparing(SessionListEntry::lastModifiedAt).reversed());
        return List.copyOf(entries);
    }

    public void delete(String sessionId) {
        try {
            Files.deleteIfExists(jsonlPath(sessionId));
            Files.deleteIfExists(statePath(sessionId));
            Files.deleteIfExists(legacyJsonPath(sessionId));
            Files.deleteIfExists(legacyJsonBakPath(sessionId));
        } catch (IOException e) {
            throw new SessionStorageException("Failed to delete session " + sessionId, e);
        }
    }

    public Optional<ConversationSession> loadIfExists(String sessionId) {
        try {
            return loadInternal(sessionId);
        } catch (IOException exception) {
            throw new SessionStorageException("Failed to load transcript for session " + sessionId, exception);
        }
    }

    public Optional<SessionSummary> findMostRecent() {
        return listSessions().stream().findFirst();
    }

    private Optional<ConversationSession> loadInternal(String sessionId) throws IOException {
        Path jsonl = jsonlPath(sessionId);
        if (Files.isRegularFile(jsonl)) {
            return Optional.of(loadJsonlSession(jsonl));
        }

        Path legacy = legacyJsonPath(sessionId);
        if (Files.isRegularFile(legacy)) {
            return Optional.of(loadLegacySession(legacy));
        }
        return Optional.empty();
    }

    private void saveTranscript(ConversationSession session) throws IOException {
        Path jsonl = jsonlPath(session.sessionId());
        List<Message> messages = session.messages();
        int persistedCount = session.persistedMessageCount();
        boolean canAppend = Files.isRegularFile(jsonl)
                && !session.transcriptRewriteRequired()
                && persistedCount <= messages.size();

        if (!canAppend) {
            rewriteTranscript(jsonl, session);
            session.markMessagesPersisted(messages.size());
            return;
        }

        appendMessages(jsonl, messages.subList(persistedCount, messages.size()));
        session.markMessagesPersisted(messages.size());
    }

    private void rewriteTranscript(Path target, ConversationSession session) throws IOException {
        AtomicFiles.writeAtomically(target, tempFile -> {
            try (OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.TRUNCATE_EXISTING)) {
                writeJsonLine(out, transcriptHeader(session));
                for (Message message : session.messages()) {
                    writeJsonLine(out, transcriptMessage(message));
                }
            }
        });
    }

    private void appendMessages(Path target, List<Message> newMessages) throws IOException {
        if (newMessages.isEmpty()) {
            return;
        }
        try (OutputStream out = Files.newOutputStream(
                target, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            for (Message message : newMessages) {
                writeJsonLine(out, transcriptMessage(message));
            }
        }
    }

    private void writeStateFile(ConversationSession session) throws IOException {
        Path target = statePath(session.sessionId());
        AtomicFiles.writeAtomically(
                target,
                tempFile -> mapper.writerWithDefaultPrettyPrinter().writeValue(
                        tempFile.toFile(),
                        serializeState(session)));
    }

    private ConversationSession loadJsonlSession(Path jsonlPath) throws IOException {
        JsonlTranscript transcript = readJsonlTranscript(jsonlPath);
        ConversationSession session = new ConversationSession(
                transcript.sessionId(),
                transcript.createdAt(),
                transcript.workingDirectory(),
                transcript.messages(),
                List.of(),
                List.of(),
                List.of());
        applyState(session, loadStateOrDefault(transcript.sessionId()));
        session.markMessagesPersisted(transcript.messages().size());
        return session;
    }

    private ConversationSession loadLegacySession(Path legacyPath) throws IOException {
        JsonNode root = mapper.readTree(legacyPath.toFile());
        ConversationSession session = deserializeLegacySession(root);
        session.markMessagesPersisted(session.messages().size());
        return session;
    }

    private JsonlTranscript readJsonlTranscript(Path jsonlPath) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(jsonlPath, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.isBlank()) {
                throw new SessionStorageException("Missing JSONL transcript header");
            }
            JsonNode headerNode = mapper.readTree(headerLine);
            if (!HEADER_KIND.equals(headerNode.path("recordType").asText())) {
                throw new SessionStorageException("Invalid JSONL transcript header");
            }
            if (!FORMAT_JSONL.equals(headerNode.path("format").asText())) {
                throw new SessionStorageException("Unsupported transcript format");
            }

            int schemaVersion = optionalSchemaVersion(headerNode);
            if (schemaVersion > SchemaMigrator.CURRENT) {
                throw new SessionStorageException("Unsupported transcript schemaVersion: " + schemaVersion);
            }

            String sessionId = requiredText(headerNode, "sessionId");
            Instant createdAt = Instant.parse(requiredText(headerNode, "createdAt"));
            Path workingDirectory = Path.of(requiredText(headerNode, "workingDirectory"));
            List<Message> messages = new ArrayList<>();

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode node;
                try {
                    node = mapper.readTree(line);
                } catch (IOException exception) {
                    if (looksLikePartialFinalLine(line, reader)) {
                        break;
                    }
                    throw new SessionStorageException("Corrupted transcript line in " + jsonlPath, exception);
                }
                if (!MESSAGE_KIND.equals(node.path("recordType").asText())) {
                    throw new SessionStorageException("Unsupported transcript record type");
                }
                messages.add(deserializeMessage(node.path("message")));
            }

            return new JsonlTranscript(sessionId, createdAt, workingDirectory, List.copyOf(messages));
        }
    }

    private boolean looksLikePartialFinalLine(String line, BufferedReader reader) throws IOException {
        return !reader.ready() && !line.stripTrailing().endsWith("}");
    }

    private Optional<ObjectNode> loadStateOrDefault(String sessionId) throws IOException {
        Path statePath = statePath(sessionId);
        if (!Files.isRegularFile(statePath)) {
            return Optional.empty();
        }
        JsonNode root = mapper.readTree(statePath.toFile());
        if (!(root instanceof ObjectNode objectNode)) {
            throw new SessionStorageException("Session state file must be an object");
        }
        return Optional.of(objectNode);
    }

    private void applyState(ConversationSession session, Optional<ObjectNode> stateNode) {
        ObjectNode state = stateNode.orElseGet(this::defaultStateNode);
        SessionMode workflowMode = readWorkflowMode(state);
        session.setWorkflowMode(workflowMode);
        session.setPlanMode(state.path("planMode").asBoolean(false));
        session.setPermissionMode(PermissionMode.parse(
                state.path("permissionMode").asText(null)).orElse(PermissionMode.DEFAULT));

        List<PlanItem> tasks = new ArrayList<>();
        JsonNode tasksNode = state.path("tasks");
        if (tasksNode.isArray()) {
            for (JsonNode taskNode : tasksNode) {
                tasks.add(deserializeTask(taskNode));
            }
        }
        session.plan().replaceItems(tasks);

        List<TodoItem> todos = new ArrayList<>();
        JsonNode todosNode = state.path("todos");
        if (todosNode.isArray()) {
            for (JsonNode todoNode : todosNode) {
                todos.add(new TodoItem(
                        todoNode.path("content").asText(""),
                        todoNode.path("status").asText("pending")));
            }
        }
        session.plan().replaceTodos(todos);

        List<String> history = new ArrayList<>();
        JsonNode historyNode = state.path("history");
        if (historyNode.isArray()) {
            for (JsonNode entry : historyNode) {
                history.add(entry.asText());
            }
        }
        session.replaceInputHistory(history);

        List<String> loadedDeferredTools = new ArrayList<>();
        JsonNode loadedDeferredToolsNode = state.path("loadedDeferredTools");
        if (loadedDeferredToolsNode.isArray()) {
            for (JsonNode entry : loadedDeferredToolsNode) {
                String toolName = entry.asText("");
                if (!toolName.isBlank()) {
                    loadedDeferredTools.add(toolName);
                }
            }
        }
        session.replaceLoadedDeferredTools(loadedDeferredTools);

        if (workflowMode == SessionMode.LONG_RUNNING) {
            session.setLongRunningStage(readLongRunningStage(state, workflowMode));
            session.setLongRunningTaskId(optionalText(state, "longRunningTaskId"));
            session.setLongRunningTaskDirectory(optionalText(state, "longRunningTaskDirectory"));
            session.setLongRunningTaskTitle(optionalText(state, "longRunningTaskTitle"));
            session.setLongRunningReason(optionalText(state, "longRunningReason"));
            session.setLongRunningPlanSummary(optionalText(state, "longRunningPlanSummary"));
            session.setLongRunningWorkerSession(state.path("longRunningWorkerSession").asBoolean(false));
            JsonNode pendingRequest = state.get("pendingLongRunningTransitionRequest");
            if (pendingRequest != null && !pendingRequest.isNull()) {
                session.setPendingLongRunningTransitionRequest(deserializeTransitionRequest(pendingRequest));
            }
        }
    }

    private ObjectNode defaultStateNode() {
        ObjectNode node = mapper.createObjectNode();
        node.put("workflowMode", SessionMode.COMMON.id());
        node.put("permissionMode", PermissionMode.DEFAULT.id());
        node.putArray("tasks");
        node.putArray("todos");
        node.putArray("history");
        node.putArray("loadedDeferredTools");
        return node;
    }

    private ObjectNode transcriptHeader(ConversationSession session) {
        ObjectNode root = mapper.createObjectNode();
        root.put("recordType", HEADER_KIND);
        root.put("format", FORMAT_JSONL);
        root.put("schemaVersion", SchemaMigrator.CURRENT);
        root.put("sessionId", session.sessionId());
        root.put("createdAt", session.createdAt().toString());
        root.put("workingDirectory", session.workingDirectory().toString());
        return root;
    }

    private ObjectNode transcriptMessage(Message message) {
        ObjectNode root = mapper.createObjectNode();
        root.put("recordType", MESSAGE_KIND);
        root.set("message", serializeMessage(message));
        return root;
    }

    private ObjectNode serializeState(ConversationSession session) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", SchemaMigrator.CURRENT);
        root.put("sessionId", session.sessionId());
        root.put("workflowMode", session.workflowMode().id());
        root.put("planMode", session.isPlanMode());
        root.put("permissionMode", session.permissionMode().id());
        root.put("persistedMessageCount", session.messages().size());
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
                .ifPresent(request -> root.set(
                        "pendingLongRunningTransitionRequest",
                        serializeTransitionRequest(request)));

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

        ArrayNode loadedDeferredToolsNode = mapper.createArrayNode();
        for (String toolName : session.loadedDeferredTools().stream().sorted().toList()) {
            loadedDeferredToolsNode.add(toolName);
        }
        root.set("loadedDeferredTools", loadedDeferredToolsNode);
        return root;
    }

    private Optional<SessionSummary> readSummary(Path path) {
        return switch (fileKind(path)) {
            case JSONL -> readJsonlSummary(path);
            case LEGACY_JSON -> readLegacySummary(path);
            case STATE_JSON, OTHER -> Optional.empty();
        };
    }

    private Optional<SessionListEntry> readEntryIfRelevant(Path path) {
        return switch (fileKind(path)) {
            case JSONL -> Optional.of(readJsonlEntry(path));
            case LEGACY_JSON -> Optional.of(readLegacyEntry(path));
            case STATE_JSON, OTHER -> Optional.empty();
        };
    }

    private SessionListEntry readJsonlEntry(Path path) {
        try {
            return readJsonlSummary(path)
                    .<SessionListEntry>map(summary -> summary)
                    .orElseGet(() -> corrupted(path, "invalid jsonl transcript"));
        } catch (RuntimeException exception) {
            return corrupted(path, exception.getMessage());
        }
    }

    private SessionListEntry readLegacyEntry(Path path) {
        try {
            return readLegacySummary(path)
                    .<SessionListEntry>map(summary -> summary)
                    .orElseGet(() -> corrupted(path, "invalid legacy transcript"));
        } catch (RuntimeException exception) {
            return corrupted(path, exception.getMessage());
        }
    }

    private Optional<SessionSummary> readJsonlSummary(Path path) {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                return Optional.empty();
            }
            JsonNode header = mapper.readTree(headerLine);
            if (!HEADER_KIND.equals(header.path("recordType").asText())
                    || !FORMAT_JSONL.equals(header.path("format").asText())) {
                return Optional.empty();
            }
            String sessionId = requiredText(header, "sessionId");
            Instant createdAt = Instant.parse(requiredText(header, "createdAt"));
            Path workingDirectory = Path.of(requiredText(header, "workingDirectory"));
            int messageCount = readPersistedCountFromState(sessionId).orElseGet(() -> countJsonlMessages(path));
            return Optional.of(new SessionSummary(
                    sessionId,
                    createdAt,
                    workingDirectory,
                    messageCount,
                    path.toAbsolutePath().normalize(),
                    Files.getLastModifiedTime(path).toInstant()));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private int countJsonlMessages(Path path) {
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            reader.readLine();
            while (reader.readLine() != null) {
                count++;
            }
            return count;
        } catch (IOException exception) {
            throw new SessionStorageException("Failed to count JSONL messages", exception);
        }
    }

    private Optional<Integer> readPersistedCountFromState(String sessionId) {
        Path statePath = statePath(sessionId);
        if (!Files.isRegularFile(statePath)) {
            return Optional.empty();
        }
        try {
            JsonNode root = mapper.readTree(statePath.toFile());
            JsonNode persistedMessageCount = root.get("persistedMessageCount");
            if (persistedMessageCount != null && persistedMessageCount.canConvertToInt()) {
                return Optional.of(persistedMessageCount.asInt());
            }
            return Optional.empty();
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    private Optional<SessionSummary> readLegacySummary(Path path) {
        try {
            JsonNode root = mapper.readTree(path.toFile());
            int schemaVersion = optionalSchemaVersion(root);
            if (schemaVersion > SchemaMigrator.CURRENT) {
                return Optional.empty();
            }
            JsonNode messagesNode = root.path("messages");
            if (!messagesNode.isArray()) {
                return Optional.empty();
            }
            return Optional.of(new SessionSummary(
                    requiredText(root, "sessionId"),
                    Instant.parse(requiredText(root, "createdAt")),
                    Path.of(requiredText(root, "workingDirectory")),
                    messagesNode.size(),
                    path.toAbsolutePath().normalize(),
                    Files.getLastModifiedTime(path).toInstant()));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private SessionListEntry.Corrupted corrupted(Path path, String reason) {
        Instant mtime;
        try {
            mtime = Files.getLastModifiedTime(path).toInstant();
        } catch (IOException ignored) {
            mtime = Instant.EPOCH;
        }
        return new SessionListEntry.Corrupted(
                path.toAbsolutePath().normalize(),
                mtime,
                reason != null ? reason : "unknown");
    }

    private boolean prefers(SessionListEntry candidate, SessionListEntry existing) {
        boolean candidateJsonl = candidate.path().getFileName().toString().endsWith(".jsonl");
        boolean existingJsonl = existing.path().getFileName().toString().endsWith(".jsonl");
        if (candidateJsonl != existingJsonl) {
            return candidateJsonl;
        }
        return candidate.lastModifiedAt().isAfter(existing.lastModifiedAt());
    }

    private String dedupKey(SessionListEntry entry) {
        String fileName = entry.path().getFileName().toString();
        if (fileName.endsWith(".jsonl")) {
            return fileName.substring(0, fileName.length() - ".jsonl".length());
        }
        if (fileName.endsWith(".json")) {
            return fileName.substring(0, fileName.length() - ".json".length());
        }
        return fileName;
    }

    private FileKind fileKind(Path path) {
        String fileName = path.getFileName().toString();
        if (fileName.endsWith(".state.json")) {
            return FileKind.STATE_JSON;
        }
        if (fileName.endsWith(".jsonl")) {
            return FileKind.JSONL;
        }
        if (fileName.endsWith(".bak")) {
            return FileKind.OTHER;
        }
        if (fileName.endsWith(".json")) {
            return FileKind.LEGACY_JSON;
        }
        return FileKind.OTHER;
    }

    private Path jsonlPath(String sessionId) {
        return rootDirectory.resolve(SessionIdPolicy.validate(sessionId) + ".jsonl");
    }

    private Path statePath(String sessionId) {
        return rootDirectory.resolve(SessionIdPolicy.validate(sessionId) + ".state.json");
    }

    private Path legacyJsonPath(String sessionId) {
        return rootDirectory.resolve(SessionIdPolicy.validate(sessionId) + ".json");
    }

    private Path legacyJsonBakPath(String sessionId) {
        return rootDirectory.resolve(SessionIdPolicy.validate(sessionId) + ".json.bak");
    }

    private void writeJsonLine(OutputStream out, JsonNode node) throws IOException {
        out.write(mapper.writeValueAsBytes(node));
        out.write('\n');
        out.flush();
    }

    private ConversationSession deserializeLegacySession(JsonNode root) {
        int schemaVersion = optionalSchemaVersion(root);
        if (schemaVersion > SchemaMigrator.CURRENT) {
            throw new SessionStorageException("Unsupported transcript schemaVersion: " + schemaVersion);
        }
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

        ConversationSession session = new ConversationSession(
                sessionId,
                createdAt,
                workingDirectory,
                messages,
                deserializeTasks(migrated.path("tasks")),
                deserializeTodos(migrated.path("todos")),
                deserializeHistory(migrated.path("history")));
        session.setWorkflowMode(readWorkflowMode(migrated));
        session.setPlanMode(migrated.path("planMode").asBoolean(false));
        session.setPermissionMode(PermissionMode.parse(
                migrated.path("permissionMode").asText(null)).orElse(PermissionMode.DEFAULT));
        if (session.workflowMode() == SessionMode.LONG_RUNNING) {
            session.setLongRunningStage(readLongRunningStage(migrated, session.workflowMode()));
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
        List<String> loadedDeferredTools = new ArrayList<>();
        JsonNode loadedDeferredToolsNode = migrated.path("loadedDeferredTools");
        if (loadedDeferredToolsNode.isArray()) {
            for (JsonNode entry : loadedDeferredToolsNode) {
                String toolName = entry.asText("");
                if (!toolName.isBlank()) {
                    loadedDeferredTools.add(toolName);
                }
            }
        }
        session.replaceLoadedDeferredTools(loadedDeferredTools);
        return session;
    }

    private List<PlanItem> deserializeTasks(JsonNode tasksNode) {
        List<PlanItem> tasks = new ArrayList<>();
        if (tasksNode.isArray()) {
            for (JsonNode taskNode : tasksNode) {
                tasks.add(deserializeTask(taskNode));
            }
        }
        return tasks;
    }

    private List<TodoItem> deserializeTodos(JsonNode todosNode) {
        List<TodoItem> todos = new ArrayList<>();
        if (todosNode.isArray()) {
            for (JsonNode todoNode : todosNode) {
                todos.add(new TodoItem(
                        todoNode.path("content").asText(""),
                        todoNode.path("status").asText("pending")));
            }
        }
        return todos;
    }

    private List<String> deserializeHistory(JsonNode historyNode) {
        List<String> history = new ArrayList<>();
        if (historyNode.isArray()) {
            for (JsonNode entry : historyNode) {
                history.add(entry.asText());
            }
        }
        return history;
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

    private ObjectNode serializeMessage(Message message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", message.role().name());
        if (message.kind() != MessageKind.STANDARD) {
            node.put("kind", message.kind().name());
        }

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
            case ContentBlock.TerminalBlock terminalBlock -> {
                ObjectNode node = mapper.createObjectNode();
                node.put("type", "terminal");
                node.put("message", terminalBlock.message());
                node.put("reason", terminalBlock.reason().name());
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
        MessageKind kind = readMessageKind(messageNode, role);
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
            case USER, ASSISTANT -> messageForRole(role, contentBlocks, kind);
        };
    }

    private Message messageForRole(MessageRole role, List<ContentBlock> contentBlocks, MessageKind kind) {
        if (contentBlocks.size() == 1 && contentBlocks.getFirst() instanceof ContentBlock.TextBlock textBlock) {
            if (role == MessageRole.USER && kind == MessageKind.CONTROLLER_EVENT) {
                return Message.controllerEvent(textBlock.text());
            }
            return Message.of(role, List.of(new ContentBlock.TextBlock(textBlock.text())), kind);
        }
        return Message.of(role, contentBlocks, kind);
    }

    private MessageKind readMessageKind(JsonNode messageNode, MessageRole role) {
        if (role == MessageRole.SYSTEM) {
            return MessageKind.STANDARD;
        }
        String raw = optionalText(messageNode, "kind");
        if (raw == null || raw.isBlank()) {
            return MessageKind.STANDARD;
        }
        try {
            return MessageKind.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            throw new SessionStorageException("Unsupported message kind: " + raw);
        }
    }

    private ContentBlock deserializeContentBlock(JsonNode blockNode) {
        String type = requiredText(blockNode, "type");
        return switch (type) {
            case "text" -> new ContentBlock.TextBlock(requiredText(blockNode, "text"));
            case "terminal" -> new ContentBlock.TerminalBlock(
                    requiredText(blockNode, "message"),
                    madacode.core.model.FinishReason.valueOf(requiredText(blockNode, "reason")));
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

    private record JsonlTranscript(
            String sessionId,
            Instant createdAt,
            Path workingDirectory,
            List<Message> messages) {
    }

    private enum FileKind {
        JSONL,
        STATE_JSON,
        LEGACY_JSON,
        OTHER
    }
}
