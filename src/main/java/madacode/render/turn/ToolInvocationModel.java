package madacode.render.turn;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.render.tool.ToolProgressLine;
import madacode.render.tool.ToolProgressSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

final class ToolInvocationModel {

    enum State {
        QUEUED,
        WAITING_PERMISSION,
        RUNNING,
        SUCCESS,
        FAILED,
        DENIED
    }

    private static final int MAX_PROGRESS_LINES_STORED = 200;

    final String toolUseId;
    final String toolName;
    final ObjectNode input;
    final ToolActivityDescriptor descriptor;

    private final List<ToolProgressLine> progressLines = new ArrayList<>();
    private int droppedProgressLineCount;
    private int droppedActivityLineCount;
    private State state = State.QUEUED;
    private long durationMs;
    private String resultOutput = "";
    private String permissionDenyReason;
    private int permissionSelectedIdx;

    ToolInvocationModel(String toolUseId, String toolName, ObjectNode input,
                        ToolActivityDescriptor descriptor) {
        this.toolUseId = Objects.requireNonNull(toolUseId, "toolUseId");
        this.toolName = Objects.requireNonNull(toolName, "toolName");
        this.input = Objects.requireNonNull(input, "input");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    State state() {
        return state;
    }

    long durationMs() {
        return durationMs;
    }

    String resultOutput() {
        return resultOutput;
    }

    String permissionDenyReason() {
        return permissionDenyReason;
    }

    int permissionSelectedIdx() {
        return permissionSelectedIdx;
    }

    void markStarted() {
        if (!isFinalized()) {
            state = State.RUNNING;
        }
    }

    void enterPermissionPhase() {
        if (!isFinalized()) {
            state = State.WAITING_PERMISSION;
            permissionSelectedIdx = 0;
        }
    }

    void resolvePermission() {
        if (state == State.WAITING_PERMISSION) {
            state = State.RUNNING;
        }
    }

    void markDenied(String reason) {
        state = State.DENIED;
        permissionDenyReason = reason;
    }

    void setPermissionSelected(int idx) {
        permissionSelectedIdx = Math.max(0, idx);
    }

    void setResultOutput(boolean success, String output) {
        resultOutput = output != null ? output : "";
    }

    void finalizeTool(boolean success, long durationMs) {
        if (state == State.DENIED) {
            this.durationMs = durationMs;
            return;
        }
        state = success ? State.SUCCESS : State.FAILED;
        this.durationMs = durationMs;
    }

    void appendProgress(ToolProgressLine line) {
        if (line == null || line.text().isBlank()) {
            return;
        }
        if (state == State.QUEUED) {
            state = State.RUNNING;
        }
        progressLines.add(line);
        if (progressLines.size() > MAX_PROGRESS_LINES_STORED) {
            ToolProgressLine dropped = progressLines.removeFirst();
            droppedProgressLineCount++;
            if (dropped.kind() == ToolProgressLine.Kind.ACTIVITY) {
                droppedActivityLineCount++;
            }
        }
    }

    ToolProgressSnapshot progressSnapshot() {
        return new ToolProgressSnapshot(
                progressLines,
                droppedProgressLineCount,
                droppedActivityLineCount);
    }

    boolean isFinalized() {
        return state == State.SUCCESS || state == State.FAILED || state == State.DENIED;
    }

    boolean isPureQueued() {
        return state == State.QUEUED
                && progressLines.isEmpty()
                && resultOutput.isBlank();
    }
}
