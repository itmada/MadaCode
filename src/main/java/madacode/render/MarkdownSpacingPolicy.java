package madacode.render;

import java.util.ArrayList;
import java.util.List;

final class MarkdownSpacingPolicy {

    private MarkdownSpacingPolicy() {
    }

    static List<String> applyLeadingBlank(List<String> lines, boolean needsLeadingBlank) {
        if (!needsLeadingBlank || lines.isEmpty() || lines.get(0).isEmpty()) {
            return lines;
        }
        ArrayList<String> out = new ArrayList<>(lines.size() + 1);
        out.add("");
        out.addAll(lines);
        return out;
    }
}
