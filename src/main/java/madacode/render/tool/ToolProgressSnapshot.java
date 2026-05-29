package madacode.render.tool;

import java.util.List;
import java.util.Objects;

public record ToolProgressSnapshot(
        List<ToolProgressLine> lines,
        int droppedLineCount,
        int droppedActivityCount
) {

    public ToolProgressSnapshot {
        lines = List.copyOf(Objects.requireNonNullElse(lines, List.of()));
        droppedLineCount = Math.max(0, droppedLineCount);
        droppedActivityCount = Math.max(0, droppedActivityCount);
    }

    public static ToolProgressSnapshot of(List<ToolProgressLine> lines) {
        return new ToolProgressSnapshot(lines, 0, 0);
    }
}
