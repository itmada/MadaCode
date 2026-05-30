package madacode.tool;

import java.util.ArrayList;
import java.util.List;

record LineChangeStats(int added, int removed) {

    private static final long MAX_LCS_CELLS = 2_000_000L;

    static LineChangeStats between(String original, String updated) {
        String oldText = TextFileSupport.normalizeLineSeparators(original == null ? "" : original);
        String newText = TextFileSupport.normalizeLineSeparators(updated == null ? "" : updated);
        if (oldText.equals(newText)) {
            return new LineChangeStats(0, 0);
        }

        List<String> oldLines = logicalLines(oldText);
        List<String> newLines = logicalLines(newText);

        int prefix = commonPrefix(oldLines, newLines);
        int oldEnd = oldLines.size() - 1;
        int newEnd = newLines.size() - 1;
        while (oldEnd >= prefix && newEnd >= prefix
                && oldLines.get(oldEnd).equals(newLines.get(newEnd))) {
            oldEnd--;
            newEnd--;
        }

        int oldChanged = Math.max(0, oldEnd - prefix + 1);
        int newChanged = Math.max(0, newEnd - prefix + 1);
        int commonInside = commonLineCount(
                oldLines.subList(prefix, prefix + oldChanged),
                newLines.subList(prefix, prefix + newChanged));

        return new LineChangeStats(newChanged - commonInside, oldChanged - commonInside);
    }

    String formatPlain() {
        return "+" + added + " -" + removed;
    }

    private static List<String> logicalLines(String content) {
        if (content.isEmpty()) {
            return List.of();
        }
        String[] parts = content.split("\n", -1);
        int length = parts.length;
        if (content.endsWith("\n")) {
            length--;
        }
        List<String> lines = new ArrayList<>(Math.max(0, length));
        for (int i = 0; i < length; i++) {
            lines.add(parts[i]);
        }
        return lines;
    }

    private static int commonPrefix(List<String> oldLines, List<String> newLines) {
        int max = Math.min(oldLines.size(), newLines.size());
        int i = 0;
        while (i < max && oldLines.get(i).equals(newLines.get(i))) {
            i++;
        }
        return i;
    }

    private static int commonLineCount(List<String> oldLines, List<String> newLines) {
        if (oldLines.isEmpty() || newLines.isEmpty()) {
            return 0;
        }
        long cells = (long) oldLines.size() * (long) newLines.size();
        if (cells > MAX_LCS_CELLS) {
            return 0;
        }

        int[] previous = new int[newLines.size() + 1];
        int[] current = new int[newLines.size() + 1];
        for (String oldLine : oldLines) {
            for (int j = 1; j <= newLines.size(); j++) {
                if (oldLine.equals(newLines.get(j - 1))) {
                    current[j] = previous[j - 1] + 1;
                } else {
                    current[j] = Math.max(previous[j], current[j - 1]);
                }
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[newLines.size()];
    }
}
