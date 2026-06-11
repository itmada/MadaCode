package madacode.core.session;

import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.model.MessageRole;
import madacode.core.model.MetaEvent;
import madacode.core.model.TokenUsage;

import madacode.permission.PermissionMode;
import madacode.plan.PlanItem;
import madacode.plan.PlanStatus;
import madacode.plan.TodoItem;
import madacode.tool.ReadFileState;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Conversation state — messages, plan, history, mode, token usage.
 *
 * <h3>Threading model</h3>
 * <p>State is published via {@link AtomicReference}-held immutable snapshots.
 * Readers always see a stable {@code List.copyOf}-style snapshot; writers
 * compose new snapshots and publish atomically.
 *
 * <p>Writes are expected to come from a single thread (the QueryEngine main
 * loop or REPL turn driver). Concurrent writes from multiple threads are
 * not supported and may lose updates — there is no CAS retry loop because
 * the invariants enforced in {@link #addMessage} cannot be re-evaluated
 * inside a retry without subtle ordering bugs.
 *
 * <p>Reads are safe from any thread (streaming I/O, tool workers, renderers).
 * The returned lists are immutable; iterating them while writers append
 * cannot throw {@link java.util.ConcurrentModificationException}.
 *
 * <p>{@code tokenUsage} is updated atomically via {@code updateAndGet} and
 * may be read from any thread.
 */
public class ConversationSession {

    private final String sessionId;
    private final Instant createdAt;
    private final Path workingDirectory;
    private final AtomicReference<List<Message>> messagesRef =
            new AtomicReference<>(List.of());
    private final AtomicReference<List<Message>> pendingControllerEventsRef =
            new AtomicReference<>(List.of());
    private final PlanStore planStore;
    private final AtomicReference<List<String>> inputHistoryRef =
            new AtomicReference<>(List.of());
    private volatile SessionMode workflowMode = SessionMode.COMMON;
    private volatile boolean planMode;
    private volatile PermissionMode permissionMode = PermissionMode.DEFAULT;
    private volatile LongRunningStage longRunningStage;
    private volatile String longRunningTaskId;
    private volatile String longRunningTaskDirectory;
    private volatile String longRunningTaskTitle;
    private volatile String longRunningReason;
    private volatile String longRunningPlanSummary;
    private volatile boolean longRunningWorkerSession;
    private volatile LongRunningTransitionRequest pendingLongRunningTransitionRequest;
    private volatile madacode.longrunning.WorkerReport lastWorkerReport;
    private final AtomicReference<TokenUsage> tokenUsageRef =
            new AtomicReference<>(TokenUsage.ZERO);
    private final SessionEventBus eventBus = new SessionEventBus();
    private volatile StreamingAssistantHandle currentStream;
    private final ReadFileState readFileState = new ReadFileState();
    private final AtomicReference<Set<String>> loadedDeferredToolsRef =
            new AtomicReference<>(Set.of());

    public ConversationSession() {
        this(UUID.randomUUID().toString(), Instant.now(),
                Path.of(System.getProperty("user.dir")),
                List.of(Message.system("Session initialized.")),
                List.of(), List.of(), List.of());
    }

    public ConversationSession(Path workingDirectory) {
        this(UUID.randomUUID().toString(), Instant.now(), workingDirectory,
                List.of(Message.system("Session initialized.")),
                List.of(), List.of(), List.of());
    }

    public ConversationSession(
            String sessionId,
            Instant createdAt,
            Path workingDirectory,
            List<Message> initialMessages) {
        this(sessionId, createdAt, workingDirectory, initialMessages,
                List.of(), List.of(), List.of());
    }

    public ConversationSession(
            String sessionId,
            Instant createdAt,
            Path workingDirectory,
            List<Message> initialMessages,
            List<PlanItem> tasks,
            List<TodoItem> todos,
            List<String> inputHistory) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory").toAbsolutePath().normalize();

        List<Message> initial = List.copyOf(Objects.requireNonNull(initialMessages, "initialMessages"));
        if (initial.isEmpty()) {
            initial = List.of(Message.system("Session initialized."));
        }
        this.messagesRef.set(initial);
        this.planStore = new PlanStore(tasks, todos);
        this.inputHistoryRef.set(List.copyOf(Objects.requireNonNull(inputHistory, "inputHistory")));
    }

    public void replaceMessages(List<Message> newMessages) {
        messagesRef.set(List.copyOf(newMessages));
        tokenUsageRef.set(TokenUsage.ZERO);
    }

    public void addMessage(Message message) {
        if (currentStream != null) {
            throw new IllegalStateException(
                    "Cannot add message while an assistant stream is open; "
                            + "finalize or abandon the stream first.");
        }
        List<Message> snapshot = messagesRef.get();
        if (!snapshot.isEmpty()) {
            Message tail = snapshot.getLast();
            if (tail.role() == message.role() && message.role() != MessageRole.SYSTEM) {
                throw new IllegalStateException(
                        "Consecutive same-role messages are not allowed: "
                                + message.role() + " after " + tail.role()
                                + ". Use a SYSTEM message for warnings/markers.");
            }
        }
        int index = snapshot.size();
        messagesRef.set(append(snapshot, message));
        eventBus.fireMessageAppended(index, message);
    }

    /**
     * Append a controller-side runtime fact that must be visible to future model
     * calls. The event is stored as a user-role message because provider
     * serializers intentionally drop historical SYSTEM messages.
     */
    public void addControllerEvent(String domain, Map<String, String> fields) {
        addControllerEvent(domain, fields, false);
    }

    public void enqueueControllerEvent(String domain, Map<String, String> fields) {
        pendingControllerEventsRef.set(append(
                pendingControllerEventsRef.get(),
                buildControllerEventMessage(domain, fields)));
    }

    public void flushPendingControllerEvents() {
        List<Message> pending = pendingControllerEventsRef.get();
        if (pending.isEmpty()) {
            return;
        }
        pendingControllerEventsRef.set(List.of());
        for (Message event : pending) {
            addControllerEventMessage(event);
        }
    }

    private void addControllerEvent(String domain, Map<String, String> fields, boolean queued) {
        Message event = buildControllerEventMessage(domain, fields);
        if (queued) {
            pendingControllerEventsRef.set(append(pendingControllerEventsRef.get(), event));
            return;
        }
        addControllerEventMessage(event);
    }

    private Message buildControllerEventMessage(String domain, Map<String, String> fields) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(fields, "fields");
        LinkedHashMap<String, String> ordered = new LinkedHashMap<>();
        ordered.put("time", OffsetDateTime.now(ZoneId.systemDefault()).toString());
        ordered.putAll(fields);
        return Message.user(controllerEventText(domain, ordered));
    }

    private void addControllerEventMessage(Message event) {
        if (currentStream != null) {
            throw new IllegalStateException(
                    "Cannot add controller event while an assistant stream is open; "
                            + "finalize or abandon the stream first.");
        }
        List<Message> snapshot = messagesRef.get();
        if (!snapshot.isEmpty() && snapshot.getLast().role() == MessageRole.USER) {
            appendMessageWithoutStreamCheck(Message.system("[controller-event separator]"));
        }
        appendMessageWithoutStreamCheck(event);
        appendMessageWithoutStreamCheck(Message.system("[controller-event barrier]"));
    }

    /** Append a streamed message silently — listeners receive
     *  onAssistantStreamFinalized instead. */
    void appendStreamedMessage(Message message) {
        messagesRef.set(append(messagesRef.get(), message));
        currentStream = null;
    }

    /** Discard the active stream without appending a message (used on cancellation). */
    void clearStream() {
        currentStream = null;
    }

    // ---- Listeners -------------------------------------------------------

    public SessionEventBus eventBus() {
        return eventBus;
    }

    @Deprecated
    public void addListener(SessionListener listener) {
        eventBus.addListener(listener);
    }

    @Deprecated
    public void removeListener(SessionListener listener) {
        eventBus.removeListener(listener);
    }

    /** Replay all current messages into a listener without firing transient events. */
    public void replay(SessionListener listener) {
        List<Message> snapshot = messagesRef.get();
        for (int i = 0; i < snapshot.size(); i++) {
            listener.onMessageAppended(i, snapshot.get(i));
        }
    }

    // ---- Firing helpers (called by QueryEngine / ToolOrchestrator) -------

    @Deprecated
    public void fireToolExecutionReached(String toolUseId, String toolName, ObjectNode input) {
        eventBus.fireToolExecutionReached(toolUseId, toolName, input);
    }

    @Deprecated
    public void fireToolExecutionStarted(String toolUseId, String toolName, ObjectNode input) {
        eventBus.fireToolExecutionStarted(toolUseId, toolName, input);
    }

    @Deprecated
    public void fireToolExecutionCompleted(String toolUseId, boolean success, long durationMs) {
        eventBus.fireToolExecutionCompleted(toolUseId, success, durationMs);
    }

    @Deprecated
    public void fireToolResultAvailable(String toolUseId, boolean success, String output) {
        eventBus.fireToolResultAvailable(toolUseId, success, output);
    }

    @Deprecated
    public void fireToolExecutionProgress(String toolUseId, String progressText) {
        eventBus.fireToolExecutionProgress(toolUseId, progressText);
    }

    @Deprecated
    public void fireToolExecutionActivity(String toolUseId, String activityText) {
        eventBus.fireToolExecutionActivity(toolUseId, activityText);
    }

    @Deprecated
    public void fireToolExecutionMetric(String toolUseId, String metricText) {
        eventBus.fireToolExecutionMetric(toolUseId, metricText);
    }

    @Deprecated
    public void fireTurnEnd() {
        eventBus.fireTurnEnd();
    }

    /**
     * Fires a meta event and applies session-owned token accounting.
     *
     * <p>Token usage updates intentionally remain on {@code ConversationSession};
     * {@link SessionEventBus} only broadcasts the event.
     */
    public void fireMetaEvent(MetaEvent meta) {
        if (meta instanceof MetaEvent.TokenReport report) {
            tokenUsageRef.updateAndGet(curr -> curr.plus(report.usage()));
        }
        eventBus.fireMetaEvent(meta);
    }

    /** Begin a streaming assistant message. Only one may be open at a time. */
    public StreamingAssistantHandle beginAssistantStream() {
        if (currentStream != null) {
            throw new IllegalStateException("stream already open");
        }
        currentStream = new StreamingAssistantHandle(this, messagesRef.get().size());
        return currentStream;
    }

    public String sessionId() {
        return sessionId;
    }

    public ReadFileState readFileState() {
        return readFileState;
    }

    public Set<String> loadedDeferredTools() {
        return loadedDeferredToolsRef.get();
    }

    public void loadDeferredTool(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        loadedDeferredToolsRef.updateAndGet(current -> {
            if (current.contains(toolName)) {
                return current;
            }
            Set<String> next = new HashSet<>(current);
            next.add(toolName);
            return Set.copyOf(next);
        });
    }

    public void loadDeferredTools(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return;
        }
        loadedDeferredToolsRef.updateAndGet(current -> {
            Set<String> next = new HashSet<>(current);
            for (String toolName : toolNames) {
                if (toolName != null && !toolName.isBlank()) {
                    next.add(toolName);
                }
            }
            return Set.copyOf(next);
        });
    }

    public void replaceLoadedDeferredTools(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            loadedDeferredToolsRef.set(Set.of());
            return;
        }
        Set<String> next = new HashSet<>();
        for (String toolName : toolNames) {
            if (toolName != null && !toolName.isBlank()) {
                next.add(toolName);
            }
        }
        loadedDeferredToolsRef.set(Set.copyOf(next));
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public List<Message> messages() {
        return messagesRef.get();
    }

    public List<String> inputHistory() {
        return inputHistoryRef.get();
    }

    public void addInput(String input) {
        inputHistoryRef.set(append(inputHistoryRef.get(), input));
    }

    public String title() {
        return messagesRef.get().stream()
                .filter(m -> m.role() == MessageRole.USER)
                .map(ConversationSession::firstText)
                .filter(s -> !isControllerEventText(s))
                .filter(s -> !s.isBlank())
                .findFirst()
                .map(s -> truncateTitle(s.strip()))
                .orElse("(empty session)");
    }

    private static String firstText(Message message) {
        if (message.contentBlocks().isEmpty()) {
            return "";
        }
        var first = message.contentBlocks().getFirst();
        return first instanceof ContentBlock.TextBlock tb ? tb.text() : "";
    }

    private void appendMessageWithoutStreamCheck(Message message) {
        List<Message> snapshot = messagesRef.get();
        if (!snapshot.isEmpty()) {
            Message tail = snapshot.getLast();
            if (tail.role() == message.role() && message.role() != MessageRole.SYSTEM) {
                throw new IllegalStateException(
                        "Consecutive same-role messages are not allowed: "
                                + message.role() + " after " + tail.role()
                                + ". Use a SYSTEM message for warnings/markers.");
            }
        }
        int index = snapshot.size();
        messagesRef.set(append(snapshot, message));
        eventBus.fireMessageAppended(index, message);
    }

    private static String controllerEventText(String domain, Map<String, String> fields) {
        StringBuilder text = new StringBuilder("[controller-event][")
                .append(normalizeControllerEventToken(domain))
                .append("]");
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String key = normalizeControllerEventToken(entry.getKey());
            String value = normalizeControllerEventValue(entry.getValue());
            if (key.isBlank() || value.isBlank()) {
                continue;
            }
            text.append('\n').append(key).append(": ").append(value);
        }
        return text.toString();
    }

    private static String normalizeControllerEventToken(String value) {
        return value == null ? "" : value.strip()
                .replaceAll("[^A-Za-z0-9_.-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
    }

    private static String normalizeControllerEventValue(String value) {
        return value == null ? "" : value.strip()
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ");
    }

    private static boolean isControllerEventText(String text) {
        return text != null && text.startsWith("[controller-event][");
    }

    // ---- Plan mode ----------------------------------------------------------

    public boolean isPlanMode() {
        return planMode;
    }

    public void setPlanMode(boolean planMode) {
        this.planMode = planMode;
    }

    // ---- Workflow mode --------------------------------------------------

    public SessionMode workflowMode() {
        return workflowMode;
    }

    public void setWorkflowMode(SessionMode workflowMode) {
        SessionMode resolved = workflowMode == null ? SessionMode.COMMON : workflowMode;
        this.workflowMode = resolved;
        if (resolved == SessionMode.COMMON) {
            this.longRunningStage = null;
            this.longRunningTaskId = null;
            this.longRunningTaskDirectory = null;
            this.longRunningTaskTitle = null;
            this.longRunningReason = null;
            this.longRunningPlanSummary = null;
            this.longRunningWorkerSession = false;
            this.pendingLongRunningTransitionRequest = null;
            this.lastWorkerReport = null;
        }
    }

    // ---- Permission mode ----------------------------------------------

    public PermissionMode permissionMode() {
        return permissionMode;
    }

    public void setPermissionMode(PermissionMode permissionMode) {
        this.permissionMode = permissionMode == null ? PermissionMode.DEFAULT : permissionMode;
    }

    // ---- Long-running mode -------------------------------------------

    public boolean isLongRunningModeActive() {
        return workflowMode == SessionMode.LONG_RUNNING && longRunningStage != null;
    }

    public LongRunningStage longRunningStage() {
        return longRunningStage;
    }

    public void setLongRunningStage(LongRunningStage longRunningStage) {
        requireLongRunningMode("longRunningStage", longRunningStage);
        this.longRunningStage = longRunningStage;
    }

    public String longRunningTaskId() {
        return longRunningTaskId;
    }

    public void setLongRunningTaskId(String longRunningTaskId) {
        requireLongRunningMode("longRunningTaskId", longRunningTaskId);
        this.longRunningTaskId = longRunningTaskId;
    }

    public String longRunningTaskDirectory() {
        return longRunningTaskDirectory;
    }

    public void setLongRunningTaskDirectory(String longRunningTaskDirectory) {
        requireLongRunningMode("longRunningTaskDirectory", longRunningTaskDirectory);
        this.longRunningTaskDirectory = longRunningTaskDirectory;
    }

    public String longRunningTaskTitle() {
        return longRunningTaskTitle;
    }

    public void setLongRunningTaskTitle(String longRunningTaskTitle) {
        requireLongRunningMode("longRunningTaskTitle", longRunningTaskTitle);
        this.longRunningTaskTitle = normalizeOptionalLongRunningText(longRunningTaskTitle);
    }

    public String longRunningReason() {
        return longRunningReason;
    }

    public void setLongRunningReason(String longRunningReason) {
        requireLongRunningMode("longRunningReason", longRunningReason);
        this.longRunningReason = normalizeOptionalLongRunningText(longRunningReason);
    }

    public String longRunningPlanSummary() {
        return longRunningPlanSummary;
    }

    public void setLongRunningPlanSummary(String longRunningPlanSummary) {
        requireLongRunningMode("longRunningPlanSummary", longRunningPlanSummary);
        this.longRunningPlanSummary = normalizeOptionalLongRunningText(longRunningPlanSummary);
    }

    public boolean isLongRunningWorkerSession() {
        return longRunningWorkerSession;
    }

    public void setLongRunningWorkerSession(boolean value) {
        this.longRunningWorkerSession = value;
    }

    public Optional<LongRunningTransitionRequest> pendingLongRunningTransitionRequest() {
        return Optional.ofNullable(pendingLongRunningTransitionRequest);
    }

    public void setPendingLongRunningTransitionRequest(LongRunningTransitionRequest request) {
        requireLongRunningMode("pendingLongRunningTransitionRequest", request);
        this.pendingLongRunningTransitionRequest = request;
    }

    public void clearPendingLongRunningTransitionRequest() {
        this.pendingLongRunningTransitionRequest = null;
    }

    public Optional<madacode.longrunning.WorkerReport> lastWorkerReport() {
        return Optional.ofNullable(lastWorkerReport);
    }

    public void recordWorkerReport(madacode.longrunning.WorkerReport report) {
        this.lastWorkerReport = report;
    }

    public void clearWorkerReport() {
        this.lastWorkerReport = null;
    }

    private void requireLongRunningMode(String fieldName, Object value) {
        if (value != null && workflowMode != SessionMode.LONG_RUNNING) {
            throw new IllegalStateException(
                    fieldName + " requires workflowMode LONG_RUNNING");
        }
    }

    private static String normalizeOptionalLongRunningText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isBlank() ? null : normalized;
    }

    public TokenUsage tokenUsage() {
        return tokenUsageRef.get();
    }

    public void resetTokenUsage() {
        tokenUsageRef.set(TokenUsage.ZERO);
    }

    // ---- Plan & Todo support ------------------------------------------------

    public PlanStore plan() {
        return planStore;
    }

    /**
     * Encapsulates plan item and todo state with atomic snapshot semantics.
     */
    public static final class PlanStore {
        private final AtomicReference<List<PlanItem>> itemsRef;
        private final AtomicReference<List<TodoItem>> todosRef;

        PlanStore(List<PlanItem> items, List<TodoItem> todos) {
            this.itemsRef = new AtomicReference<>(List.copyOf(Objects.requireNonNull(items, "items")));
            this.todosRef = new AtomicReference<>(List.copyOf(Objects.requireNonNull(todos, "todos")));
        }

        public List<PlanItem> items() {
            return itemsRef.get();
        }

        public List<TodoItem> todos() {
            return todosRef.get();
        }

        public void add(PlanItem item) {
            itemsRef.set(append(itemsRef.get(), item));
        }

        public void update(PlanItem updated) {
            List<PlanItem> snapshot = itemsRef.get();
            for (int i = 0; i < snapshot.size(); i++) {
                if (snapshot.get(i).id().equals(updated.id())) {
                    List<PlanItem> next = new ArrayList<>(snapshot);
                    next.set(i, updated);
                    itemsRef.set(List.copyOf(next));
                    return;
                }
            }
            throw new IllegalArgumentException("Plan item not found: " + updated.id());
        }

        public Optional<PlanItem> find(String id) {
            return itemsRef.get().stream().filter(t -> t.id().equals(id)).findFirst();
        }

        public void replaceTodos(List<TodoItem> newTodos) {
            todosRef.set(List.copyOf(newTodos));
        }

        public void clearAll() {
            itemsRef.set(List.of());
            todosRef.set(List.of());
        }

        public String nextId() {
            return String.valueOf(itemsRef.get().size() + 1);
        }

        /** Returns IDs of all items whose {@code blockedBy} includes {@code itemId}. */
        public Set<String> findBlockedItems(String itemId) {
            return itemsRef.get().stream()
                    .filter(p -> p.blockedBy().contains(itemId))
                    .map(PlanItem::id)
                    .collect(java.util.stream.Collectors.toSet());
        }

        /** Returns the set of incomplete dependency IDs blocking the given item. */
        public Set<String> validateCanStart(PlanItem item) {
            if (item.blockedBy().isEmpty()) {
                return Set.of();
            }
            Set<String> blockers = new HashSet<>();
            for (String depId : item.blockedBy()) {
                Optional<PlanItem> dep = find(depId);
                if (dep.isEmpty() || dep.get().status() != PlanStatus.COMPLETED) {
                    blockers.add(depId);
                }
            }
            return blockers;
        }

        /** DFS check for cycles. */
        public boolean hasCyclicDependency(PlanItem item, String targetId) {
            Set<String> visited = new HashSet<>();
            return dfsCycle(item.id(), targetId, visited);
        }

        private boolean dfsCycle(String fromId, String targetId, Set<String> visited) {
            if (targetId.equals(fromId)) {
                return true;
            }
            if (!visited.add(targetId)) {
                return false;
            }
            Optional<PlanItem> target = find(targetId);
            if (target.isEmpty()) {
                return false;
            }
            for (String depId : target.get().blockedBy()) {
                if (dfsCycle(fromId, depId, visited)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static String truncateTitle(String s) {
        return s.length() <= 50 ? s : s.substring(0, 47) + "...";
    }

    /** Build a new immutable list = snapshot + element. Centralised so the
     *  copy strategy can be swapped (e.g. for a persistent data structure)
     *  without touching call sites. */
    private static <T> List<T> append(List<T> snapshot, T element) {
        List<T> next = new ArrayList<>(snapshot.size() + 1);
        next.addAll(snapshot);
        next.add(element);
        return List.copyOf(next);
    }

}
