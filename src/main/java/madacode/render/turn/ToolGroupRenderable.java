package madacode.render.turn;

import madacode.render.Spinner;
import madacode.render.tool.DisplayStatus;
import madacode.render.tool.ToolActivitySkip;
import madacode.render.tool.ToolDisplay;
import madacode.render.tool.ToolDisplayRegistry;
import madacode.tui.TerminalText;
import madacode.tui.theme.Tk;
import madacode.tui.widget.ApprovalPanel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ToolGroupRenderable implements Renderable {

    private final ToolDisplayRegistry displayRegistry;
    private final Spinner spinner = Spinner.dots();
    private final List<ToolInvocationModel> invocations = new ArrayList<>();
    private final Map<String, ToolInvocationModel> byId = new LinkedHashMap<>();
    private boolean marginIssued;

    public ToolGroupRenderable(ToolDisplayRegistry displayRegistry) {
        this.displayRegistry = Objects.requireNonNull(displayRegistry, "displayRegistry");
    }

    public synchronized void add(ToolInvocationModel invocation) {
        if (byId.containsKey(invocation.toolUseId)) {
            return;
        }
        invocations.add(invocation);
        byId.put(invocation.toolUseId, invocation);
    }

    public synchronized boolean markStarted(String toolUseId) {
        ToolInvocationModel invocation = byId.get(toolUseId);
        if (invocation == null) {
            return false;
        }
        invocation.markStarted();
        return true;
    }

    public synchronized boolean setResultOutput(String toolUseId, boolean success, String output) {
        ToolInvocationModel invocation = byId.get(toolUseId);
        if (invocation == null) {
            return false;
        }
        invocation.setResultOutput(success, output);
        return true;
    }

    public synchronized boolean finalizeTool(String toolUseId, boolean success, long durationMs) {
        ToolInvocationModel invocation = byId.get(toolUseId);
        if (invocation == null) {
            return false;
        }
        invocation.finalizeTool(success, durationMs);
        return true;
    }

    public synchronized boolean appendProgress(
            String toolUseId, madacode.render.tool.ToolProgressLine line) {
        ToolInvocationModel invocation = byId.get(toolUseId);
        if (invocation == null) {
            return false;
        }
        invocation.appendProgress(line);
        return true;
    }

    public synchronized boolean enterPermissionPhase(String toolUseId) {
        ToolInvocationModel invocation = byId.get(toolUseId);
        if (invocation == null) {
            return false;
        }
        invocation.enterPermissionPhase();
        return true;
    }

    public synchronized boolean resolvePermission(String toolUseId) {
        ToolInvocationModel invocation = byId.get(toolUseId);
        if (invocation == null) {
            return false;
        }
        invocation.resolvePermission();
        return true;
    }

    public synchronized boolean markDenied(String toolUseId, String reason) {
        ToolInvocationModel invocation = byId.get(toolUseId);
        if (invocation == null) {
            return false;
        }
        invocation.markDenied(reason);
        return true;
    }

    public synchronized boolean setPermissionSelected(String toolUseId, int idx) {
        ToolInvocationModel invocation = byId.get(toolUseId);
        if (invocation == null) {
            return false;
        }
        invocation.setPermissionSelected(idx);
        return true;
    }

    public synchronized void finalizeUnfinishedAsFailed() {
        for (ToolInvocationModel invocation : invocations) {
            if (!invocation.isFinalized()) {
                invocation.finalizeTool(false, 0);
            }
        }
    }

    @Override
    public synchronized List<String> render(int maxWidth) {
        if (isPureQueued()) {
            return List.of();
        }
        int safeWidth = Math.max(1, maxWidth);
        List<String> lines = new ArrayList<>();
        boolean active = !isFinalized();
        String glyph = active ? spinner.tick() : "●";
        String header = statusColor(active ? DisplayStatus.RUNNING : DisplayStatus.INFO, glyph)
                + " "
                + Tk.toolName(active ? "Exploring" : "Explored")
                + Tk.dim(" · ")
                + summary();
        lines.add(fit(header, safeWidth));

        ToolInvocationModel waitingPermission = null;
        for (int i = 0; i < invocations.size(); i++) {
            ToolInvocationModel invocation = invocations.get(i);
            if (invocation.state() == ToolInvocationModel.State.WAITING_PERMISSION
                    && waitingPermission == null) {
                waitingPermission = invocation;
            }
            boolean last = i == invocations.size() - 1;
            lines.add(fit("  " + Tk.dim(last ? "└" : "├") + " " + invocationLine(invocation), safeWidth));
            for (String detail : failureDetails(invocation)) {
                lines.add(fit("  " + Tk.dim(last ? " " : "│") + "   " + Tk.dim(detail), safeWidth));
            }
        }

        if (waitingPermission != null) {
            ToolDisplay display = display(waitingPermission);
            lines.add("");
            lines.addAll(ApprovalPanel.renderInlineApproval(
                    safeWidth,
                    waitingPermission.permissionSelectedIdx(),
                    display.title(),
                    display.summary()));
        }
        return lines;
    }

    @Override
    public synchronized boolean isFinalized() {
        return !invocations.isEmpty()
                && invocations.stream().allMatch(ToolInvocationModel::isFinalized);
    }

    @Override
    public synchronized boolean isMarginIssued() {
        return marginIssued;
    }

    @Override
    public synchronized void markMarginIssued() {
        marginIssued = true;
    }

    private boolean isPureQueued() {
        return !invocations.isEmpty()
                && invocations.stream().allMatch(ToolInvocationModel::isPureQueued);
    }

    private String summary() {
        long completed = invocations.stream().filter(ToolInvocationModel::isFinalized).count();
        long failed = invocations.stream()
                .filter(i -> i.state() == ToolInvocationModel.State.FAILED
                        || i.state() == ToolInvocationModel.State.DENIED)
                .count();
        String count = invocations.size() + " action" + (invocations.size() == 1 ? "" : "s");
        if (failed > 0) {
            return count + " · " + Tk.failure(failed + " failed");
        }
        if (completed == invocations.size()) {
            return count;
        }
        return completed + "/" + invocations.size() + " done";
    }

    private String invocationLine(ToolInvocationModel invocation) {
        ToolDisplay display = display(invocation);
        String target = invocation.descriptor.target();
        String line = statusBullet(invocation.state())
                + " "
                + Tk.accent(invocation.descriptor.action());
        if (!target.isBlank()) {
            line += " " + targetStyle(invocation.descriptor.kind(), target);
        }
        if (!display.summary().isBlank()) {
            line += Tk.dim(" · ") + display.summary();
        }
        return line;
    }

    private List<String> failureDetails(ToolInvocationModel invocation) {
        if (invocation.state() != ToolInvocationModel.State.FAILED
                && invocation.state() != ToolInvocationModel.State.DENIED) {
            return List.of();
        }
        return display(invocation).detailLines();
    }

    private ToolDisplay display(ToolInvocationModel invocation) {
        return switch (invocation.state()) {
            case QUEUED -> displayRegistry.renderQueued(invocation.toolName, invocation.input);
            case WAITING_PERMISSION, RUNNING -> displayRegistry.renderRunning(
                    invocation.toolName,
                    invocation.input,
                    invocation.progressSnapshot());
            case SUCCESS -> displayRegistry.renderSuccess(
                    invocation.toolName,
                    invocation.input,
                    invocation.resultOutput(),
                    invocation.durationMs());
            case FAILED -> {
                ToolDisplay failed = displayRegistry.renderError(
                        invocation.toolName,
                        invocation.input,
                        invocation.resultOutput(),
                        invocation.durationMs());
                ToolDisplay compact = ToolActivitySkip.compactDisplay(failed, invocation.resultOutput());
                yield compact != null ? compact : failed;
            }
            case DENIED -> displayRegistry.renderDenied(
                    invocation.toolName,
                    invocation.input,
                    invocation.permissionDenyReason() != null
                            ? invocation.permissionDenyReason()
                            : invocation.resultOutput(),
                    invocation.durationMs());
        };
    }

    private static String statusBullet(ToolInvocationModel.State state) {
        return switch (state) {
            case QUEUED -> Tk.dim("•");
            case WAITING_PERMISSION, RUNNING -> Tk.running("•");
            case SUCCESS -> Tk.success("•");
            case FAILED, DENIED -> Tk.failure("•");
        };
    }

    private static String statusColor(DisplayStatus status, String text) {
        return switch (status) {
            case RUNNING -> Tk.running(text);
            case SUCCESS -> Tk.success(text);
            case FAILED, DENIED -> Tk.failure(text);
            case INFO -> Tk.dim(text);
        };
    }

    private static String targetStyle(ToolActivityKind kind, String target) {
        return switch (kind) {
            case READ, LIST -> Tk.filePath(target);
            case SEARCH -> Tk.toolArg(target);
            case INSPECT, EXEC, UNKNOWN, WRITE, EDIT, AGENT, PLAN -> Tk.toolArg(target);
        };
    }

    private static String fit(String line, int maxWidth) {
        return TerminalText.displayWidth(line) <= maxWidth
                ? line
                : TerminalText.fitEnd(line, maxWidth);
    }
}
