package madacode.longrunning;

import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionListener;
import madacode.core.session.SessionMode;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Temporary {@link SessionListener} that audits whether an EXECUTING turn
 * produced any observable progress.
 *
 * <p>When the turn ends, if the session is still in EXECUTING mode and no
 * {@code longrun_task_update} action was <em>successfully completed</em> during
 * the turn, the tracker appends a harness warning to the task's
 * {@code progress.txt}.
 *
 * <p>Progress is tracked via a pending→commit pattern: {@code onToolExecutionStarted}
 * captures the action into a pending map keyed by toolUseId, and
 * {@code onToolExecutionCompleted} promotes it to recorded only when
 * {@code success == true}. This prevents failed tool calls from being
 * incorrectly counted as progress.
 *
 * <p>This listener is designed to be registered per-turn by
 * {@link madacode.cli.mode.LongRunningModeHandler} and removed after the turn
 * completes. It does not crash the turn on missing progress — it only adds
 * observability.
 */
public final class LongRunningTurnTracker implements SessionListener {

    private static final Set<String> PROGRESS_ACTIONS = Set.of(
            "append_progress",
            "mark_feature_passed",
            "record_issue",
            "resolve_issue",
            "update_issue_status",
            "mark_task_complete",
            "cancel_task",
            "write_initial_feature_list");

    private static final String TOOL_NAME = "longrun_task_update";

    private final ConversationSession session;
    private final LongRunningTaskStore store;

    /**
     * Pending actions keyed by toolUseId. On tool start we record the action
     * here; on tool completion we promote it to {@link #recordedActions} only
     * if the tool succeeded.
     */
    private final Map<String, String> pendingActions = new ConcurrentHashMap<>();

    private final Set<String> recordedActions = ConcurrentHashMap.newKeySet();
    private volatile boolean turnEnded;

    public LongRunningTurnTracker(ConversationSession session, LongRunningTaskStore store) {
        this.session = session;
        this.store = store;
    }

    /**
     * Returns {@code true} if at least one progress-producing action was
     * successfully completed during this turn.
     */
    public boolean hasProgress() {
        return !recordedActions.isEmpty();
    }

    @Override
    public void onToolExecutionStarted(String toolUseId, String toolName, ObjectNode input) {
        if (!TOOL_NAME.equals(toolName)) {
            return;
        }
        String action = input.path("action").asText("").strip().toLowerCase(Locale.ROOT);
        if (PROGRESS_ACTIONS.contains(action)) {
            pendingActions.put(toolUseId, action);
        }
    }

    @Override
    public void onToolExecutionCompleted(String toolUseId, boolean success, long durationMs) {
        String action = pendingActions.remove(toolUseId);
        if (action != null && success) {
            recordedActions.add(action);
        }
    }

    @Override
    public void onTurnEnd() {
        if (turnEnded) {
            return; // already audited — prevent duplicate HARNESS WARNING
        }
        turnEnded = true;
        auditProgress();
    }

    private void auditProgress() {
        // Only audit EXECUTING turns
        if (session.workflowMode() != SessionMode.LONG_RUNNING) {
            return;
        }
        LongRunningStage stage = session.longRunningStage();
        if (stage != LongRunningStage.EXECUTING) {
            return;
        }
        String taskId = session.longRunningTaskId();
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        // If progress was made, no warning needed
        if (hasProgress()) {
            appendTurnEvent(taskId, true,
                    "EXECUTING turn completed with longrun_task_update progress.",
                    Map.of("recordedActions", String.join(",", recordedActions)));
            return;
        }
        // Append a harness warning to progress.txt
        try {
            String warning = "[HARNESS WARNING] EXECUTING turn completed without any "
                    + "successful longrun_task_update action. No progress was recorded for this turn."
                    + System.lineSeparator();
            store.appendProgress(taskId, warning);
            appendTurnEvent(taskId, false, warning.strip(), Map.of("recordedActions", ""));
        } catch (RuntimeException ignored) {
            // Best-effort: don't crash the turn if the store write fails
        }
    }

    private void appendTurnEvent(
            String taskId,
            boolean success,
            String message,
            Map<String, String> details) {
        try {
            store.appendEvent(taskId, LongRunningTaskEvent.of(
                    "turn_completed",
                    taskId,
                    session.sessionId(),
                    session.longRunningStage() == null ? null : session.longRunningStage().name(),
                    null,
                    success,
                    message,
                    details));
        } catch (RuntimeException ignored) {
            // Best-effort diagnostics only.
        }
    }
}
