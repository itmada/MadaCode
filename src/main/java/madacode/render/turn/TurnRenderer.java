package madacode.render.turn;

import madacode.core.ContentBlock;
import madacode.core.FinishReason;
import madacode.core.Message;
import madacode.core.MessageRole;
import madacode.core.MetaEvent;
import madacode.core.SessionListener;
import madacode.render.ThinkingVerbs;
import madacode.render.BlockSpacing;
import madacode.render.tool.ToolProgressLine;
import madacode.render.tool.ToolDisplayRegistry;
import madacode.tui.JLineScreen;
import madacode.tui.Screen;
import madacode.tui.theme.Tk;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Single {@link SessionListener} that drives the {@link TurnView} render tree.
 *
 * <p>Owns ALL visual output during a turn: assistant text, tool cards,
 * thinking spinner, and error display.  The turn has three termination
 * paths (normal completion, cancellation, API error) that all funnel
 * through a single cleanup path.
 */
public final class TurnRenderer implements SessionListener {

    private final TurnView turnView;
    private final Screen screen;
    private final ToolDisplayRegistry toolDisplays = ToolDisplayRegistry.defaults();

    private final Map<String, ToolCardRenderable> toolCards = new LinkedHashMap<>();
    private final Map<String, String> activeToolDescriptions = new LinkedHashMap<>();

    private AssistantTextRenderable currentText;
    private TurnStatusRenderable statusLine;
    private int activeStreamIndex = -1;
    private boolean aborted;

    public TurnRenderer(TurnView turnView, Screen screen) {
        this.turnView = Objects.requireNonNull(turnView, "turnView");
        this.screen = Objects.requireNonNull(screen, "screen");
    }

    // ---- streaming text ---------------------------------------------------

    private synchronized void dismissStatusLine() {
        if (statusLine != null) {
            statusLine.finalizeStatus();
            turnView.remove(statusLine);
            statusLine = null;
        }
    }

    private synchronized void showStatusLine(String message, TurnStatusRenderable.Mode mode) {
        if (statusLine == null) {
            statusLine = new TurnStatusRenderable(message, mode, turnView::markDirty);
            turnView.add(statusLine);
        } else {
            statusLine.updateMessage(message, mode);
        }
    }

    private synchronized void maybeRestoreToolStatus() {
        String last = null;
        for (String value : activeToolDescriptions.values()) {
            last = value;
        }
        if (last == null || last.isBlank()) {
            dismissStatusLine();
            return;
        }
        showStatusLine(last, TurnStatusRenderable.Mode.TOOL_USE);
    }

    @Override
    public synchronized void onAssistantTextChunk(int index, String chunk) {
        dismissStatusLine();
        if (activeStreamIndex != index) {
            currentText = null;
            activeStreamIndex = index;
        }
        if (currentText == null) {
            currentText = new AssistantTextRenderable();
            turnView.add(currentText);
        }
        currentText.append(chunk);
        turnView.markDirty();
    }

    @Override
    public synchronized void onAssistantStreamFinalized(int index) {
        if (currentText != null) {
            currentText.finalizeText();
            currentText = null;
        }
        activeStreamIndex = -1;
        if (!activeToolDescriptions.isEmpty()) {
            maybeRestoreToolStatus();
        } else {
            dismissStatusLine();
        }
        turnView.markDirty();
    }

    // ---- tool blocks ------------------------------------------------------

    @Override
    public synchronized void onAssistantBlockAppended(int index, ContentBlock block) {
        if (block instanceof ContentBlock.ToolUseBlock tu) {
            dismissStatusLine();
            if (toolCards.containsKey(tu.id())) return;
            if (currentText != null) {
                currentText.finalizeText();
                currentText = null;
            }
            ToolCardRenderable card = new ToolCardRenderable(
                    tu.id(), tu.name(), tu.input(), toolDisplays);
            toolCards.put(tu.id(), card);
            turnView.add(card);
            turnView.markDirty();
        }
    }

    // ---- tool execution ---------------------------------------------------

    @Override
    public synchronized void onMessageAppended(int index, Message message) {
        if (message.role() != MessageRole.USER) return;
        for (ContentBlock block : message.contentBlocks()) {
            if (block instanceof ContentBlock.ToolResultBlock tr) {
                ToolCardRenderable card = toolCards.get(tr.toolUseId());
                if (card != null) {
                    card.setResultOutput(tr.success(), tr.content());
                    turnView.markDirty();
                }
            }
        }
    }

    @Override
    public synchronized void onToolExecutionStarted(String toolUseId, String toolName, ObjectNode input) {
        String activity = toolDisplays.activityDescription(toolName, input);
        activeToolDescriptions.put(toolUseId, activity);
        showStatusLine(activity, TurnStatusRenderable.Mode.TOOL_USE);
        turnView.markDirty();
    }

    @Override
    public synchronized void onToolExecutionCompleted(String toolUseId, boolean success, long durationMs) {
        activeToolDescriptions.remove(toolUseId);
        ToolCardRenderable card = toolCards.get(toolUseId);
        if (card != null) {
            card.finalizeTool(success, durationMs);
        }
        maybeRestoreToolStatus();
        turnView.markDirty();
    }

    @Override
    public synchronized void onToolExecutionProgress(String toolUseId, String progressText) {
        ToolCardRenderable card = toolCards.get(toolUseId);
        if (card != null) {
            card.appendProgress(ToolProgressLine.output(progressText));
            turnView.markDirty();
        }
    }

    @Override
    public synchronized void onToolExecutionActivity(String toolUseId, String activityText) {
        ToolCardRenderable card = toolCards.get(toolUseId);
        if (card != null) {
            card.appendProgress(ToolProgressLine.activity(activityText));
            turnView.markDirty();
        }
    }

    @Override
    public synchronized void onToolExecutionMetric(String toolUseId, String metricText) {
        ToolCardRenderable card = toolCards.get(toolUseId);
        if (card != null) {
            card.appendProgress(ToolProgressLine.metric(metricText));
            turnView.markDirty();
        }
    }

    // ---- meta events ------------------------------------------------------

    @Override
    public synchronized void onMetaEvent(MetaEvent meta) {
        switch (meta) {
            case MetaEvent.ModelRequestStarted s -> {
                activeToolDescriptions.clear();
                dismissStatusLine();
                showStatusLine(ThinkingVerbs.pick(0) + "...", TurnStatusRenderable.Mode.THINKING);
                turnView.markDirty();
            }
            case MetaEvent.Error e -> abortTurn(e.message(), e.reason());
            case MetaEvent.PlanModeEntered i ->
                    screen.scrollback(Tk.dim("[plan mode entered]"));
            case MetaEvent.PlanModeExited e ->
                    screen.scrollback(Tk.dim("[plan mode exited]"));
            case MetaEvent.PlanRejected r ->
                    screen.scrollback(Tk.dim("[plan rejected — staying in plan mode]"));
            default -> { /* other events handled by MetaEventRenderer */ }
        }
    }

    @Override
    public synchronized void onTurnEnd() {
        if (aborted) {
            aborted = false;
            return;
        }
        endTurn();
    }

    // ---- turn lifecycle ---------------------------------------------------

    /**
     * Abort the turn: finalize all in-progress items, spill them to
     * scrollback in order, then display the error message.
     *
     * <p>This is the single cleanup path for all error terminations
     * (cancellation, API error, max iterations/tool calls, unknown tool).
     * Error display is owned exclusively by TurnRenderer during turns.
     */
    private synchronized void abortTurn(String message, FinishReason reason) {
        dismissStatusLine();
        if (currentText != null && !currentText.isFinalized()) {
            currentText.finalizeText();
        }
        for (ToolCardRenderable card : toolCards.values()) {
            if (!card.isFinalized()) {
                card.finalizeTool(false, 0);
            }
        }
        turnView.endTurn();
        toolCards.clear();
        activeToolDescriptions.clear();
        currentText = null;
        statusLine = null;
        activeStreamIndex = -1;
        aborted = true;
        if (reason == FinishReason.PERMISSION_CANCELLED) {
            // Tool card already shows the denial; no separate banner needed.
        } else if (reason == FinishReason.CANCELLED) {
            BlockSpacing.scrollbackBlock(screen, Tk.failure(message));
        } else {
            BlockSpacing.scrollbackBlock(screen, Tk.errorTag("error") + " " + message);
        }
    }

    /** Called when the turn ends normally to spill all finalized content to scrollback. */
    public synchronized void endTurn() {
        dismissStatusLine();
        if (currentText != null && !currentText.isFinalized()) {
            currentText.finalizeText();
        }
        turnView.endTurn();
        toolCards.clear();
        activeToolDescriptions.clear();
        currentText = null;
        statusLine = null;
        activeStreamIndex = -1;
    }

    public synchronized void reset() {
        endTurn();
    }

    public synchronized void shutdown() {
        dismissStatusLine();
        turnView.shutdown();
        toolCards.clear();
        activeToolDescriptions.clear();
        currentText = null;
        statusLine = null;
        activeStreamIndex = -1;
    }

    // ---- permission support -----------------------------------------------

    public synchronized void beginPermission(String toolUseId) {
        ToolCardRenderable card = toolCards.get(toolUseId);
        if (card != null) {
            if (screen instanceof JLineScreen jls) jls.lockModal();
            card.enterPermissionPhase();
            turnView.flushNow();
        }
    }

    public synchronized void resolvePermission(String toolUseId) {
        ToolCardRenderable card = toolCards.get(toolUseId);
        if (card != null) {
            card.resolvePermission();
            if (screen instanceof JLineScreen jls) jls.unlockModal();
            turnView.markDirty();
        }
    }

    public synchronized void resolvePermission(String toolUseId, boolean denied) {
        ToolCardRenderable card = toolCards.get(toolUseId);
        if (card != null) {
            if (denied) {
                card.markDenied("User denied permission");
            } else {
                card.resolvePermission();
            }
            if (screen instanceof JLineScreen jls) jls.unlockModal();
            turnView.markDirty();
        }
    }

    public synchronized void setPermissionSelected(String toolUseId, int idx) {
        ToolCardRenderable card = toolCards.get(toolUseId);
        if (card != null) {
            card.setPermissionSelected(idx);
            turnView.markDirty();
        }
    }

    public TurnView turnView() {
        return turnView;
    }
}
