package madacode.render;

import java.util.List;

public record MarkdownLayoutFrame(List<String> permanentLines, List<String> liveLines) {

    public MarkdownLayoutFrame {
        permanentLines = permanentLines == null ? List.of() : List.copyOf(permanentLines);
        liveLines = liveLines == null ? List.of() : List.copyOf(liveLines);
    }
}
