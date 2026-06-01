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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private final PlanStore planStore;
    private final AtomicReference<List<String>> inputHistoryRef =
            new AtomicReference<>(List.of());
    private volatile boolean planMode;
    private volatile PermissionMode permissionMode = PermissionMode.DEFAULT;
    private volatile WorkflowMode workflowMode = WorkflowMode.COMMON;
    private volatile LongRunningStage longRunningStage;
    private volatile String longRunningTaskId;
    private volatile String longRunningTaskDirectory;
    private final AtomicReference<TokenUsage> tokenUsageRef =
            new AtomicReference<>(TokenUsage.ZERO);
    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private volatile StreamingAssistantHandle currentStream;
    private final ReadFileState readFileState = new ReadFileState();

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
        for (SessionListener l : listeners) {
            try {
                l.onMessageAppended(index, message);
            } catch (RuntimeException e) {
                madacode.logging.DiagnosticEventLogger.listenerCrashed("onMessageAppended", e);
            }
        }
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

    public void addListener(SessionListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(SessionListener listener) {
        listeners.remove(listener);
    }

    List<SessionListener> listenerList() {
        return listeners;
    }

    /** Replay all current messages into a listener without firing transient events. */
    public void replay(SessionListener listener) {
        List<Message> snapshot = messagesRef.get();
        for (int i = 0; i < snapshot.size(); i++) {
            listener.onMessageAppended(i, snapshot.get(i));
        }
    }

    // ---- Firing helpers (called by QueryEngine / ToolOrchestrator) -------

    public void fireToolExecutionReached(String toolUseId, String toolName, ObjectNode input) {
        for (SessionListener l : listeners) {
            try {
                l.onToolExecutionReached(toolUseId, toolName, input);
            } catch (RuntimeException e) {
                madacode.logging.DiagnosticEventLogger.listenerCrashed("onToolExecutionReached", e);
            }
        }
    }

    public void fireToolExecutionStarted(String toolUseId, String toolName, ObjectNode input) {
        for (SessionListener l : listeners) {
            try {
                l.onToolExecutionStarted(toolUseId, toolName, input);
            } catch (RuntimeException e) {
                madacode.logging.DiagnosticEventLogger.listenerCrashed("onToolExecutionStarted", e);
            }
        }
    }

    public void fireToolExecutionCompleted(String toolUseId, boolean success, long durationMs) {
        for (SessionListener l : listeners) {
            try {
                l.onToolExecutionCompleted(toolUseId, success, durationMs);
            } catch (RuntimeException e) {
                madacode.logging.DiagnosticEventLogger.listenerCrashed("onToolExecutionCompleted", e);
            }
        }
    }

    public void fireToolResultAvailable(String toolUseId, boolean success, String output) {
        for (SessionListener l : listeners) {
            try {
                l.onToolResultAvailable(toolUseId, success, output);
            } catch (RuntimeException e) {
                madacode.logging.DiagnosticEventLogger.listenerCrashed("onToolResultAvailable", e);
            }
        }
    }

    public void fireToolExecutionProgress(String toolUseId, String progressText) {
        for (SessionListener l : listeners) {
            try {
                l.onToolExecutionProgress(toolUseId, progressText);
            } catch (RuntimeException e) {
                madacode.logging.DiagnosticEventLogger.listenerCrashed("onToolExecutionProgress", e);
            }
        }
    }

    public void fireToolExecutionActivity(String toolUseId, String activityText) {
        for (SessionListener l : listeners) {
            try {
                l.onToolExecutionActivity(toolUseId, activityText);
            } catch (RuntimeException e) {
                madacode.logging.DiagnosticEventLogger.listenerCrashed("onToolExecutionActivity", e);
            }
        }
    }

    public void fireToolExecutionMetric(String toolUseId, String metricText) {
        for (SessionListener l : listeners) {
            try {
                l.onToolExecutionMetric(toolUseId, metricText);
            } catch (RuntimeException e) {
                madacode.logging.DiagnosticEventLogger.listenerCrashed("onToolExecutionMetric", e);
            }
        }
    }

    public void fireTurnEnd() {
        for (SessionListener l : listeners) {
            try {
                l.onTurnEnd();
            } catch (RuntimeException e) {
                madacode.logging.DiagnosticEventLogger.listenerCrashed("onTurnEnd", e);
            }
        }
    }

    public void fireMetaEvent(MetaEvent meta) {
        if (meta instanceof MetaEvent.TokenReport report) {
            tokenUsageRef.updateAndGet(curr -> curr.plus(report.usage()));
        }
        for (SessionListener l : listeners) {
            try {
                l.onMetaEvent(meta);
            } catch (RuntimeException e) {
                madacode.logging.DiagnosticEventLogger.listenerCrashed("onMetaEvent", e);
            }
        }
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

    // ---- Plan mode ----------------------------------------------------------

    public boolean isPlanMode() {
        return planMode;
    }

    public void setPlanMode(boolean planMode) {
        this.planMode = planMode;
    }

    // ---- Permission mode ----------------------------------------------

    public PermissionMode permissionMode() {
        return permissionMode;
    }

    public void setPermissionMode(PermissionMode permissionMode) {
        this.permissionMode = permissionMode == null ? PermissionMode.DEFAULT : permissionMode;
    }

    // ---- Workflow mode ------------------------------------------------

    public WorkflowMode workflowMode() {
        return workflowMode;
    }

    public void setWorkflowMode(WorkflowMode workflowMode) {
        WorkflowMode resolved = workflowMode == null ? WorkflowMode.COMMON : workflowMode;
        this.workflowMode = resolved;
        if (resolved == WorkflowMode.COMMON) {
            this.longRunningStage = null;
            this.longRunningTaskId = null;
            this.longRunningTaskDirectory = null;
        }
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

    private void requireLongRunningMode(String fieldName, Object value) {
        if (value != null && workflowMode != WorkflowMode.LONG_RUNNING) {
            throw new IllegalStateException(
                    fieldName + " requires workflowMode LONG_RUNNING");
        }
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
