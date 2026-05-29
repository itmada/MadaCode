package madacode.render.tool;

import java.util.List;
import java.util.Objects;

public record ToolDisplay(
        String title,
        String summary,
        List<String> detailLines,
        List<String> verboseDetailLines,
        DisplayStatus status) {

    public ToolDisplay {
        title = Objects.requireNonNullElse(title, "");
        summary = Objects.requireNonNullElse(summary, "");
        detailLines = List.copyOf(Objects.requireNonNullElse(detailLines, List.of()));
        verboseDetailLines = List.copyOf(Objects.requireNonNullElse(verboseDetailLines, detailLines));
        status = Objects.requireNonNull(status, "status");
    }

    public ToolDisplay(String title, String summary, List<String> detailLines, DisplayStatus status) {
        this(title, summary, detailLines, detailLines, status);
    }

    public static ToolDisplay running(String title, String summary) {
        return new ToolDisplay(title, summary, List.of(), DisplayStatus.RUNNING);
    }

    public static ToolDisplay success(String title, String summary, List<String> details) {
        return new ToolDisplay(title, summary, details, DisplayStatus.SUCCESS);
    }

    public static ToolDisplay failed(String title, String summary, List<String> details) {
        return new ToolDisplay(title, summary, details, DisplayStatus.FAILED);
    }

    public static ToolDisplay denied(String title, String summary, List<String> details) {
        return new ToolDisplay(title, summary, details, DisplayStatus.DENIED);
    }
}
