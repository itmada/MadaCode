package madacode.core.session;

import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.model.MessageRole;
import madacode.core.model.MetaEvent;
import madacode.core.model.TokenUsage;

import madacode.permission.PermissionMode;
import madacode.plan.CurrentPlan;
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
import java.util.function.Consumer;

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

    private static final String ASSERT_WRITER_THREAD_PROPERTY =
            "madacode.session.assertWriterThread";

    private final String sessionId;
    private final Instant createdAt;
    private final Path workingDirectory;
    private final AtomicReference<List<Message>> messagesRef =
            new AtomicReference<>(List.of());
    private final AtomicReference<List<Message>> pendingControllerEventsRef =
            new AtomicReference<>(List.of());
    private final AtomicReference<CurrentPlan> currentPlanRef =
            new AtomicReference<>(CurrentPlan.EMPTY);
    private final AtomicReference<List<String>> inputHistoryRef =
            new AtomicReference<>(List.of());
    private volatile SessionMode workflowMode = SessionMode.COMMON;
    private volatile boolean planMode;
    private volatile PermissionMode permissionMode = PermissionMode.DEFAULT;
    private volatile LongRunningSessionState longRunning;
    private final AtomicReference<TokenUsage> tokenUsageRef =
            new AtomicReference<>(TokenUsage.ZERO);
    private final SessionEventBus eventBus = new SessionEventBus();
    private volatile StreamingAssistantHandle currentStream;
    private volatile Thread writerThread;
    private volatile int persistedMessageCount;
    private volatile boolean transcriptRewriteRequired;
    private final ReadFileState readFileState = new ReadFileState();
    private final AtomicReference<Set<String>> loadedDeferredToolsRef =
            new AtomicReference<>(Set.of());
    // Notified when this session spawns a sub-agent child session. Defaults to a
    // no-op so production paths are unaffected; an observer (e.g. the eval trace
    // collector) propagates to descendants via registerSubAgent so the whole
    // agent tree is observable, not just the root turn.
    private volatile Consumer<ConversationSession> subAgentSpawnObserver = child -> {};

    public ConversationSession() {
        this(UUID.randomUUID().toString(), Instant.now(),
                Path.of(System.getProperty("user.dir")),
                List.of(Message.system("Session initialized.")),
                List.of());
    }

    public ConversationSession(Path workingDirectory) {
        this(UUID.randomUUID().toString(), Instant.now(), workingDirectory,
                List.of(Message.system("Session initialized.")),
                List.of());
    }

    public ConversationSession(
            String sessionId,
            Instant createdAt,
            Path workingDirectory,
            List<Message> initialMessages) {
        this(sessionId, createdAt, workingDirectory, initialMessages,
                List.of());
    }

    public ConversationSession(
            String sessionId,
            Instant createdAt,
            Path workingDirectory,
            List<Message> initialMessages,
            List<String> inputHistory) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory").toAbsolutePath().normalize();

        List<Message> initial = List.copyOf(Objects.requireNonNull(initialMessages, "initialMessages"));
        if (initial.isEmpty()) {
            initial = List.of(Message.system("Session initialized."));
        }
        this.messagesRef.set(initial);
        this.inputHistoryRef.set(List.copyOf(Objects.requireNonNull(inputHistory, "inputHistory")));
    }

    /**
     * Replace the full message list (e.g. after a compaction pass).
     *
     * <p>Cumulative {@link #tokenUsage()} is intentionally <em>not</em> reset
     * here: it is a session-wide cost tally consumed by {@code /cost}, and
     * compaction rewriting the transcript must not erase prior usage. The live
     * context-window gauge is computed separately (per-response
     * {@code TokenReport}s and a content estimator), so it is unaffected.
     * Callers that genuinely want a fresh tally must call
     * {@link #resetTokenUsage()} explicitly.
     */
    public void replaceMessages(List<Message> newMessages) {
        assertWriterThread();
        messagesRef.set(List.copyOf(newMessages));
        transcriptRewriteRequired = true;
    }

    public void addMessage(Message message) {
        if (currentStream != null) {
            throw new IllegalStateException(
                    "Cannot add message while an assistant stream is open; "
                            + "finalize or abandon the stream first.");
        }
        appendMessageWithoutStreamCheck(message);
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
        return Message.controllerEvent(controllerEventText(domain, ordered));
    }

    private void addControllerEventMessage(Message event) {
        if (currentStream != null) {
            throw new IllegalStateException(
                    "Cannot add controller event while an assistant stream is open; "
                            + "finalize or abandon the stream first.");
        }
        appendMessageWithoutStreamCheck(event);
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

    /**
     * Installs an observer notified whenever this session spawns a sub-agent child
     * session (see {@link #registerSubAgent}). Used by out-of-band observers such as
     * the eval trace collector to reach dynamically-spawned sub-agent sessions that
     * are otherwise invisible to a parent-session-only scan. Production leaves the
     * default no-op, so this has no effect on the interactive runtime.
     */
    public void setSubAgentSpawnObserver(Consumer<ConversationSession> observer) {
        this.subAgentSpawnObserver = Objects.requireNonNull(observer, "observer");
    }

    /**
     * Records that {@code child} was spawned as a sub-agent of this session. The
     * spawn observer is both notified of the child and propagated onto it, so a
     * grandchild spawned by the child is observed too — the whole agent tree, not
     * just one level. Called by {@code AgentRunner} right after a child session is
     * created; a no-op observer (the production default) makes this a cheap notify.
     */
    public void registerSubAgent(ConversationSession child) {
        Objects.requireNonNull(child, "child");
        Consumer<ConversationSession> observer = this.subAgentSpawnObserver;
        child.setSubAgentSpawnObserver(observer);
        observer.accept(child);
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

    int persistedMessageCount() {
        return persistedMessageCount;
    }

    boolean transcriptRewriteRequired() {
        return transcriptRewriteRequired;
    }

    void markMessagesPersisted(int messageCount) {
        this.persistedMessageCount = Math.max(0, messageCount);
        this.transcriptRewriteRequired = false;
    }

    public List<String> inputHistory() {
        return inputHistoryRef.get();
    }

    public void replaceInputHistory(List<String> newHistory) {
        inputHistoryRef.set(List.copyOf(Objects.requireNonNull(newHistory, "newHistory")));
    }

    public void addInput(String input) {
        inputHistoryRef.set(append(inputHistoryRef.get(), input));
    }

    public String title() {
        return messagesRef.get().stream()
                .filter(m -> m.role() == MessageRole.USER)
                .filter(m -> !m.isControllerEvent())
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

    private void appendMessageWithoutStreamCheck(Message message) {
        assertWriterThread();
        List<Message> snapshot = messagesRef.get();
        int index = snapshot.size();
        messagesRef.set(append(snapshot, message));
        eventBus.fireMessageAppended(index, message);
    }

    private void assertWriterThread() {
        if (!Boolean.getBoolean(ASSERT_WRITER_THREAD_PROPERTY)) {
            return;
        }
        Thread current = Thread.currentThread();
        Thread owner = writerThread;
        if (owner == null) {
            writerThread = current;
            return;
        }
        if (owner != current) {
            throw new IllegalStateException(
                    "ConversationSession writes must stay on one thread; owner="
                            + owner.getName() + ", current=" + current.getName());
        }
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
            this.longRunning = null;
        } else if (this.longRunning == null) {
            this.longRunning = new LongRunningSessionState();
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
        LongRunningSessionState state = longRunning;
        return workflowMode == SessionMode.LONG_RUNNING && state != null && state.isActive();
    }

    public LongRunningStage longRunningStage() {
        LongRunningSessionState state = longRunning;
        return state == null ? null : state.stage();
    }

    public void setLongRunningStage(LongRunningStage longRunningStage) {
        requireLongRunningMode("longRunningStage", longRunningStage);
        LongRunningSessionState state = longRunningStateFor(longRunningStage);
        if (state != null) {
            state.setStage(longRunningStage);
        }
    }

    public String longRunningTaskId() {
        LongRunningSessionState state = longRunning;
        return state == null ? null : state.taskId();
    }

    public void setLongRunningTaskId(String longRunningTaskId) {
        requireLongRunningMode("longRunningTaskId", longRunningTaskId);
        LongRunningSessionState state = longRunningStateFor(longRunningTaskId);
        if (state != null) {
            state.setTaskId(longRunningTaskId);
        }
    }

    public String longRunningTaskDirectory() {
        LongRunningSessionState state = longRunning;
        return state == null ? null : state.taskDirectory();
    }

    public void setLongRunningTaskDirectory(String longRunningTaskDirectory) {
        requireLongRunningMode("longRunningTaskDirectory", longRunningTaskDirectory);
        LongRunningSessionState state = longRunningStateFor(longRunningTaskDirectory);
        if (state != null) {
            state.setTaskDirectory(longRunningTaskDirectory);
        }
    }

    public String longRunningTaskTitle() {
        LongRunningSessionState state = longRunning;
        return state == null ? null : state.title();
    }

    public void setLongRunningTaskTitle(String longRunningTaskTitle) {
        requireLongRunningMode("longRunningTaskTitle", longRunningTaskTitle);
        LongRunningSessionState state = longRunningStateFor(longRunningTaskTitle);
        if (state != null) {
            state.setTitle(longRunningTaskTitle);
        }
    }

    public String longRunningReason() {
        LongRunningSessionState state = longRunning;
        return state == null ? null : state.reason();
    }

    public void setLongRunningReason(String longRunningReason) {
        requireLongRunningMode("longRunningReason", longRunningReason);
        LongRunningSessionState state = longRunningStateFor(longRunningReason);
        if (state != null) {
            state.setReason(longRunningReason);
        }
    }

    public String longRunningPlanSummary() {
        LongRunningSessionState state = longRunning;
        return state == null ? null : state.planSummary();
    }

    public void setLongRunningPlanSummary(String longRunningPlanSummary) {
        requireLongRunningMode("longRunningPlanSummary", longRunningPlanSummary);
        LongRunningSessionState state = longRunningStateFor(longRunningPlanSummary);
        if (state != null) {
            state.setPlanSummary(longRunningPlanSummary);
        }
    }

    public boolean isLongRunningWorkerSession() {
        LongRunningSessionState state = longRunning;
        return state != null && state.isWorkerSession();
    }

    public void setLongRunningWorkerSession(boolean value) {
        if (value) {
            requireLongRunningMode("longRunningWorkerSession", true);
            longRunningState().setWorkerSession(true);
        } else {
            LongRunningSessionState state = longRunning;
            if (state != null) {
                state.setWorkerSession(false);
            }
        }
    }

    public Optional<LongRunningWorkerReport> lastWorkerReport() {
        LongRunningSessionState state = longRunning;
        return state == null ? Optional.empty() : state.lastWorkerReport();
    }

    public void recordWorkerReport(LongRunningWorkerReport report) {
        requireLongRunningMode("lastWorkerReport", report);
        LongRunningSessionState state = longRunningStateFor(report);
        if (state != null) {
            state.recordWorkerReport(report);
        }
    }

    public void clearWorkerReport() {
        LongRunningSessionState state = longRunning;
        if (state != null) {
            state.clearWorkerReport();
        }
    }

    private void requireLongRunningMode(String fieldName, Object value) {
        LongRunningSessionState.requireLongRunningMode(workflowMode, fieldName, value);
    }

    private LongRunningSessionState longRunningState() {
        return longRunningStateFor(new Object());
    }

    private LongRunningSessionState longRunningStateFor(Object value) {
        LongRunningSessionState state = longRunning;
        if (state == null && value != null) {
            if (workflowMode != SessionMode.LONG_RUNNING) {
                throw new IllegalStateException("long-running state requires workflowMode LONG_RUNNING");
            }
            state = new LongRunningSessionState();
            longRunning = state;
        }
        return state;
    }

    public TokenUsage tokenUsage() {
        return tokenUsageRef.get();
    }

    public void resetTokenUsage() {
        tokenUsageRef.set(TokenUsage.ZERO);
    }

    // ---- Current task progress ---------------------------------------------

    public CurrentPlan currentPlan() {
        return currentPlanRef.get();
    }

    public void updateCurrentPlan(CurrentPlan plan) {
        currentPlanRef.set(plan == null ? CurrentPlan.EMPTY : plan);
    }

    public void clearCurrentPlan() {
        currentPlanRef.set(CurrentPlan.EMPTY);
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
