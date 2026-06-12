package madacode.render.turn;

import madacode.render.tool.DisplayStatus;
import madacode.render.tool.ToolActivityCardRenderer;
import madacode.render.tool.ToolDisplay;
import madacode.render.tool.ToolDisplayRegistry;
import madacode.render.tool.ToolActivitySkip;
import madacode.render.tool.ToolProgressLine;
import madacode.render.tool.ToolProgressSnapshot;
import madacode.tui.theme.Tk;
import madacode.tui.widget.ApprovalPanel;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Live rendering of a single tool invocation within a turn.
 *
 * <p>Transitions through phases:
 * <ol>
 *   <li>{@code queued} — tool_use has appeared, execution has not started</li>
 *   <li>{@code RUNNING} — spinner + title</li>
 *   <li>{@code RUNNING + permission WAITING} — adds inline permission prompt</li>
 *   <li>{@code SUCCESS / FAILED / DENIED} — final card with result lines</li>
 * </ol>
 */
public final class ToolCardRenderable implements Renderable {

    public enum PermissionPhase { NONE, WAITING, RESOLVED }
    private static final int MAX_PROGRESS_LINES_STORED = 200;
    private static final int PROGRESS_RENDER_CAP = 10;

    private final String toolUseId;
    private final String toolName;
    private final ObjectNode input;
    private final ToolDisplayRegistry displayRegistry;
    private final madacode.render.Spinner spinner = madacode.render.Spinner.dots();
    private final List<ToolProgressLine> progressLines = new ArrayList<>();
    private int droppedProgressLineCount;
    private int droppedActivityLineCount;

    private DisplayStatus status = DisplayStatus.RUNNING;
    private boolean started;
    private long durationMs;
    private PermissionPhase permissionPhase = PermissionPhase.NONE;
    private int permissionSelectedIdx;
    private String permissionDenyReason;
    private String resultOutput = "";
    private boolean success;
    private boolean finalized;
    private boolean marginIssued;

    public ToolCardRenderable(String toolUseId, String toolName, ObjectNode input,
                              ToolDisplayRegistry displayRegistry) {
        this.toolUseId = Objects.requireNonNull(toolUseId, "toolUseId");
        this.toolName = Objects.requireNonNull(toolName, "toolName");
        this.input = Objects.requireNonNull(input, "input");
        this.displayRegistry = Objects.requireNonNull(displayRegistry, "displayRegistry");
    }

    public String toolUseId() { return toolUseId; }

    public synchronized void appendProgress(String line) {
        appendProgress(ToolProgressLine.output(line));
    }

    public synchronized void appendProgress(ToolProgressLine line) {
        if (line == null || line.text().isBlank()) {
            return;
        }
        started = true;
        progressLines.add(line);
        if (progressLines.size() > MAX_PROGRESS_LINES_STORED) {
            ToolProgressLine dropped = progressLines.removeFirst();
            droppedProgressLineCount++;
            if (dropped.kind() == ToolProgressLine.Kind.ACTIVITY) {
                droppedActivityLineCount++;
            }
        }
    }

    public synchronized List<ToolProgressLine> progressLinesSnapshot() {
        return List.copyOf(progressLines);
    }

    public synchronized ToolProgressSnapshot progressSnapshot() {
        return new ToolProgressSnapshot(
                progressLines,
                droppedProgressLineCount,
                droppedActivityLineCount);
    }

    public synchronized void finalizeTool(boolean success, long durationMs) {
        if (this.status == DisplayStatus.DENIED) {
            this.durationMs = durationMs;
            return;
        }
        this.success = success;
        this.status = success ? DisplayStatus.SUCCESS : DisplayStatus.FAILED;
        this.durationMs = durationMs;
        this.finalized = true;
        this.permissionPhase = PermissionPhase.RESOLVED;
    }

    public synchronized void markDenied(String reason) {
        this.status = DisplayStatus.DENIED;
        this.permissionDenyReason = reason;
        this.finalized = true;
        this.permissionPhase = PermissionPhase.RESOLVED;
    }

    /** Record result output after tool execution completes. */
    public synchronized void setResultOutput(boolean success, String output) {
        this.success = success;
        this.resultOutput = output != null ? output : "";
    }

    public synchronized void markStarted() {
        this.started = true;
    }

    public synchronized void enterPermissionPhase() {
        this.started = true;
        this.permissionPhase = PermissionPhase.WAITING;
        this.permissionSelectedIdx = 0;
    }

    public synchronized void resolvePermission() {
        this.permissionPhase = PermissionPhase.RESOLVED;
    }

    public synchronized void setPermissionSelected(int idx) {
        this.permissionSelectedIdx = Math.max(0, idx);
    }

    public synchronized PermissionPhase permissionPhase() {
        return permissionPhase;
    }

    public synchronized boolean isPureQueued() {
        return status == DisplayStatus.RUNNING
                && !started
                && permissionPhase == PermissionPhase.NONE
                && progressLines.isEmpty()
                && resultOutput.isBlank()
                && !finalized;
    }

    @Override
    public synchronized List<String> render(int maxWidth) {
        if (isPureQueued()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        ToolDisplay display = null;

        switch (status) {
            case RUNNING -> {
                display = started
                        ? displayRegistry.renderRunning(toolName, input, progressSnapshot())
                        : displayRegistry.renderQueued(toolName, input);
                lines.addAll(ToolActivityCardRenderer.card(
                        display, maxWidth, started ? spinner.tick() : null));
                if (started && display.detailLines().isEmpty()) {
                    int total = progressLines.size();
                    int start = Math.max(0, total - PROGRESS_RENDER_CAP);
                    int hiddenCount = droppedProgressLineCount + start;
                    if (hiddenCount > 0) {
                        lines.add("  " + Tk.dim("│") + " " + Tk.dim("… (" + hiddenCount + " earlier line"
                                + (hiddenCount == 1 ? "" : "s") + " hidden)"));
                    }
                    for (int i = start; i < total; i++) {
                        lines.add("  " + Tk.dim("│") + " " + progressLines.get(i).text());
                    }
                }
            }
            case SUCCESS, FAILED, DENIED -> {
                display = switch (status) {
                    case SUCCESS -> displayRegistry.renderSuccess(
                            toolName, input, resultOutput, durationMs);
                    case FAILED -> {
                        ToolDisplay failed = displayRegistry.renderError(
                                toolName, input, resultOutput, durationMs);
                        ToolDisplay compact = ToolActivitySkip.compactDisplay(failed, resultOutput);
                        yield compact != null ? compact : failed;
                    }
                    case DENIED -> displayRegistry.renderDenied(
                            toolName,
                            input,
                            permissionDenyReason != null ? permissionDenyReason : resultOutput,
                            durationMs);
                    default -> throw new IllegalStateException("Unexpected status: " + status);
                };
                lines.addAll(ToolActivityCardRenderer.card(display, maxWidth));
            }
        }

        // Permission inline prompt
        if (permissionPhase == PermissionPhase.WAITING) {
            lines.add("");
            lines.addAll(ApprovalPanel.renderInlineApproval(
                    maxWidth,
                    permissionSelectedIdx,
                    display == null ? toolName : display.title(),
                    display == null ? "" : display.summary()));
        }

        return lines;
    }

    @Override
    public synchronized boolean isFinalized() {
        return finalized;
    }

    @Override
    public synchronized boolean isMarginIssued() { return marginIssued; }

    @Override
    public synchronized void markMarginIssued() { marginIssued = true; }
}
