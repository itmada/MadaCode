package madacode.render.turn;

import madacode.core.model.ContentBlock;
import madacode.core.model.FinishReason;
import madacode.core.model.Message;
import madacode.core.model.MessageRole;
import madacode.core.model.MetaEvent;
import madacode.core.session.SessionListener;
import madacode.render.BlockSpacing;
import madacode.render.ThinkingVerbs;
import madacode.render.tool.ToolProgressLine;
import madacode.render.tool.ToolDisplayRegistry;
import madacode.tool.ToolNames;
import madacode.tui.JLineScreen;
import madacode.tui.Screen;
import madacode.tui.theme.Tk;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
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
    private PlanPanelRenderable planPanel;
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
        // A tool is still executing, so its card owns the animation; the status
        // line yields and stays static (no competing braille spinner).
        showStatusLine(last, TurnStatusRenderable.Mode.TOOL_ACTIVE);
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
        } else if (hasPendingToolCard()) {
            // Tools are declared but none has started yet — the card is still
            // pure-queued and renders empty, so the status line is the only
            // indicator and keeps its spinner alive across the hook gap.
            showStatusLine("Preparing...", TurnStatusRenderable.Mode.WORKING);
        } else {
            dismissStatusLine();
        }
        turnView.markDirty();
    }

    @Override
    public synchronized void onAssistantStreamReset(int index) {
        if (currentText != null) {
            turnView.remove(currentText);
            currentText = null;
        }
        // Drop tool cards queued by the aborted attempt. During streaming no
        // tool has executed yet (execution starts only after the stream
        // commits), so any un-finalized card belongs to this attempt and is
        // safe to discard; finalized cards from earlier iterations are kept.
        toolCards.values().removeIf(card -> {
            if (!card.isFinalized()) {
                turnView.remove(card);
                return true;
            }
            return false;
        });
        activeStreamIndex = -1;
        turnView.markDirty();
    }

    private synchronized boolean hasPendingToolCard() {
        for (ToolCardRenderable card : toolCards.values()) {
            if (!card.isFinalized()) {
                return true;
            }
        }
        return false;
    }

    // ---- tool blocks ------------------------------------------------------

    @Override
    public synchronized void onAssistantBlockAppended(int index, ContentBlock block) {
        if (block instanceof ContentBlock.ToolUseBlock tu) {
            if (toolCards.containsKey(tu.id())) return;
            // Drop the thinking spinner so it is rebuilt below the card, not above it.
            dismissStatusLine();
            if (currentText != null) {
                currentText.finalizeText();
                currentText = null;
            }
            // update_plan has no generic tool card: the live plan panel is its
            // sole, in-place visual. Skipping the card also keeps it out of
            // scrollback so the panel never reprints per update.
            if (!ToolNames.UPDATE_PLAN.equals(tu.name())) {
                ToolCardRenderable card = new ToolCardRenderable(
                        tu.id(), tu.name(), tu.input(), toolDisplays);
                toolCards.put(tu.id(), card);
                turnView.add(card);
            }
            // The card is pure-queued and renders empty until execution starts.
            // Keep a spinner running (below the card) so the gap between block
            // arrival and onToolExecutionStarted (permission gate, hook I/O) is
            // not blank.
            showStatusLine("Preparing...", TurnStatusRenderable.Mode.WORKING);
            turnView.markDirty();
        }
    }

    // ---- tool execution ---------------------------------------------------

    @Override
    public synchronized void onMessageAppended(int index, Message message) {
        if (message.role() == MessageRole.ASSISTANT) {
            renderStaticAssistantMessage(message);
            return;
        }
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

    private void renderStaticAssistantMessage(Message message) {
        for (ContentBlock block : message.contentBlocks()) {
            if (block instanceof ContentBlock.TextBlock text && !text.text().isBlank()) {
                dismissStatusLine();
                AssistantTextRenderable renderable = new AssistantTextRenderable();
                renderable.append(text.text());
                renderable.finalizeText();
                turnView.add(renderable);
            } else if (block instanceof ContentBlock.TerminalBlock) {
                // Terminal messages are displayed by MetaEvent.Error in live turns.
            }
        }
    }

    @Override
    public synchronized void onToolExecutionStarted(String toolUseId, String toolName, ObjectNode input) {
        ToolCardRenderable card = toolCards.get(toolUseId);
        if (card != null) {
            card.markStarted();
        }
        String activity = toolDisplays.activityDescription(toolName, input);
        activeToolDescriptions.put(toolUseId, activity);
        // Card now animates its own spinner; status line yields to it.
        showStatusLine(activity, TurnStatusRenderable.Mode.TOOL_ACTIVE);
        turnView.markDirty();
    }

    @Override
    public synchronized void onToolResultAvailable(String toolUseId, boolean success, String output) {
        ToolCardRenderable card = toolCards.get(toolUseId);
        if (card != null) {
            card.setResultOutput(success, output);
            turnView.markDirty();
        }
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
            case MetaEvent.PlanUpdated p -> handlePlanUpdated(p);
            default -> { /* other events handled by MetaEventRenderer */ }
        }
    }

    /**
     * Route a plan update into the live, bottom-pinned plan panel. The first
     * update creates the panel; later updates mutate it in place so it redraws
     * instead of reprinting.
     */
    private synchronized void handlePlanUpdated(MetaEvent.PlanUpdated event) {
        if (planPanel == null) {
            planPanel = new PlanPanelRenderable(turnView::markDirty);
            turnView.setBottomPinned(planPanel);
        }
        planPanel.update(event.plan(), event.explanation());
        turnView.markDirty();
    }

    /**
     * Stop the panel's animation and hand back its one-line summary for
     * scrollback. Returns empty when there is no panel.
     */
    private synchronized List<String> finalizePlanPanel() {
        if (planPanel == null) {
            return List.of();
        }
        planPanel.markFinalized();
        List<String> summary = planPanel.render(screen.width());
        planPanel = null;
        return summary;
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
     * <p>This is the single cleanup path for turn-terminal errors
     * (cancellation, API error, max iterations). Per-tool failures
     * such as an unknown tool are NOT routed here — they surface on their own
     * tool card and let the turn continue.
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
        if (planPanel != null) {
            planPanel.markFinalized();
            planPanel = null;
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
        List<String> planSummary = finalizePlanPanel();
        turnView.endTurn();
        if (!planSummary.isEmpty()) {
            BlockSpacing.scrollbackBlock(screen, planSummary);
        }
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
        if (planPanel != null) {
            planPanel.markFinalized();
            planPanel = null;
        }
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
            // The card is now started and animates its own spinner while the
            // approval prompt is up; yield the status line so the two braille
            // spinners don't run side by side during the permission wait.
            if (statusLine != null) {
                statusLine.updateMessage("", TurnStatusRenderable.Mode.TOOL_ACTIVE);
            }
            turnView.flushNow();
        }
    }

    public synchronized void resolvePermission(String toolUseId) {
        ToolCardRenderable card = toolCards.get(toolUseId);
        if (card != null) {
            card.resolvePermission();
            dismissStatusLine();
            if (screen instanceof JLineScreen jls) jls.unlockModal();
            turnView.flushNow();
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
            dismissStatusLine();
            if (screen instanceof JLineScreen jls) jls.unlockModal();
            turnView.flushNow();
        }
    }

    public synchronized void cancelPermission(String toolUseId) {
        ToolCardRenderable card = toolCards.get(toolUseId);
        if (card != null) {
            card.markDenied("User denied permission");
            dismissStatusLine();
            if (screen instanceof JLineScreen jls) jls.unlockModal();
            turnView.flushNow();
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
