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
    private boolean closed;

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
        closed = true;
    }

    public synchronized void closeGroup() {
        closed = true;
    }

    @Override
    public synchronized List<String> render(int maxWidth) {
        if (isPureQueued()) {
            return List.of();
        }
        int safeWidth = Math.max(1, maxWidth);
        List<String> lines = new ArrayList<>();
        boolean active = hasActiveInvocation();
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
        }

        List<GroupLine> groupLines = groupLines();
        for (int i = 0; i < groupLines.size(); i++) {
            GroupLine groupLine = groupLines.get(i);
            boolean last = i == groupLines.size() - 1;
            lines.add(fit("  " + Tk.dim(last ? "└" : "├") + " " + groupLine.text(), safeWidth));
            for (String detail : groupLine.details()) {
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
        return closed
                && !invocations.isEmpty()
                && invocations.stream().allMatch(ToolInvocationModel::isFinalized);
    }

    private boolean hasActiveInvocation() {
        return invocations.stream().anyMatch(invocation -> !invocation.isFinalized());
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

    private List<GroupLine> groupLines() {
        List<GroupLine> lines = new ArrayList<>();
        int i = 0;
        while (i < invocations.size()) {
            ReadRun readRun = readRunStartingAt(i);
            if (readRun != null) {
                lines.add(new GroupLine(readRun.line(), List.of()));
                i += readRun.count();
                continue;
            }

            ToolInvocationModel invocation = invocations.get(i);
            lines.add(new GroupLine(invocationLine(invocation), failureDetails(invocation)));
            i++;
        }
        return lines;
    }

    private ReadRun readRunStartingAt(int start) {
        if (!isReadRunMember(invocations.get(start))) {
            return null;
        }

        int end = start;
        long durationMs = 0;
        int completed = 0;
        boolean running = false;
        boolean queued = false;
        while (end < invocations.size() && isReadRunMember(invocations.get(end))) {
            ToolInvocationModel invocation = invocations.get(end);
            if (invocation.state() == ToolInvocationModel.State.SUCCESS) {
                completed++;
                durationMs += Math.max(0, invocation.durationMs());
            } else if (invocation.state() == ToolInvocationModel.State.RUNNING) {
                running = true;
            } else if (invocation.state() == ToolInvocationModel.State.QUEUED) {
                queued = true;
            }
            end++;
        }

        int count = end - start;
        if (count <= 1) {
            return null;
        }
        return new ReadRun(count, completed, durationMs, running, queued);
    }

    private static boolean isReadRunMember(ToolInvocationModel invocation) {
        if (!"file_read".equals(invocation.toolName)) {
            return false;
        }
        return switch (invocation.state()) {
            case QUEUED, RUNNING, SUCCESS -> true;
            case WAITING_PERMISSION, FAILED, DENIED -> false;
        };
    }

    private static String readRunLine(
            int count, int completed, long durationMs, boolean running, boolean queued) {
        ToolInvocationModel.State state = completed == count
                ? ToolInvocationModel.State.SUCCESS
                : (running ? ToolInvocationModel.State.RUNNING : ToolInvocationModel.State.QUEUED);
        String line = statusBullet(state)
                + " "
                + Tk.accent("Read")
                + Tk.dim(" · ")
                + count + " files";
        if (completed == count) {
            return line + Tk.dim(" · ") + duration(durationMs);
        }
        if (completed > 0) {
            return line + Tk.dim(" · ") + completed + "/" + count + " done";
        }
        return line + Tk.dim(" · ") + (running ? "reading" : "queued");
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

    private static String duration(long durationMs) {
        if (durationMs < 1000) {
            return durationMs + "ms";
        }
        double seconds = durationMs / 1000.0;
        return String.format(java.util.Locale.ROOT, "%.1fs", seconds);
    }

    private record GroupLine(String text, List<String> details) {}

    private record ReadRun(int count, int completed, long durationMs, boolean running, boolean queued) {
        String line() {
            return readRunLine(count, completed, durationMs, running, queued);
        }
    }
}
