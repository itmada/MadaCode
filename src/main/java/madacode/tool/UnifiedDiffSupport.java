package madacode.tool;

import java.util.ArrayList;
import java.util.List;

final class UnifiedDiffSupport {

    private static final int CONTEXT_LINES = 3;

    private UnifiedDiffSupport() {
    }

    static List<String> computeUnifiedDiff(String original, String updated, String filePath) {
        if (original.equals(updated)) {
            return List.of();
        }
        return computeUnifiedDiff(
                splitLines(TextFileSupport.normalizeLineSeparators(original)),
                splitLines(TextFileSupport.normalizeLineSeparators(updated)),
                filePath);
    }

    static List<String> computeUnifiedDiff(
            List<String> originalLines,
            List<String> updatedLines,
            String filePath) {
        int firstDiff = 0;
        while (firstDiff < originalLines.size()
                && firstDiff < updatedLines.size()
                && originalLines.get(firstDiff).equals(updatedLines.get(firstDiff))) {
            firstDiff++;
        }
        if (firstDiff == originalLines.size() && firstDiff == updatedLines.size()) {
            return List.of();
        }

        int lastOrig = originalLines.size() - 1;
        int lastUpd = updatedLines.size() - 1;
        while (lastOrig >= firstDiff
                && lastUpd >= firstDiff
                && originalLines.get(lastOrig).equals(updatedLines.get(lastUpd))) {
            lastOrig--;
            lastUpd--;
        }

        int oldChangeCount = Math.max(0, lastOrig - firstDiff + 1);
        int newChangeCount = Math.max(0, lastUpd - firstDiff + 1);
        int contextBefore = Math.min(CONTEXT_LINES, firstDiff);
        int oldAfterStart = firstDiff + oldChangeCount;
        int newAfterStart = firstDiff + newChangeCount;
        int contextAfter = Math.min(
                CONTEXT_LINES,
                Math.min(
                        originalLines.size() - oldAfterStart,
                        updatedLines.size() - newAfterStart));

        int oldStart = firstDiff - contextBefore;
        int newStart = firstDiff - contextBefore;
        int oldHunkCount = contextBefore + oldChangeCount + contextAfter;
        int newHunkCount = contextBefore + newChangeCount + contextAfter;

        List<String> result = new ArrayList<>();
        result.add("--- " + filePath);
        result.add("+++ " + filePath);
        result.add("@@ -" + formatRangeStart(oldStart, oldHunkCount)
                + " +" + formatRangeStart(newStart, newHunkCount) + " @@");

        for (int i = oldStart; i < firstDiff; i++) {
            result.add(" " + originalLines.get(i));
        }
        for (int i = firstDiff; i < firstDiff + oldChangeCount; i++) {
            result.add("-" + originalLines.get(i));
        }
        for (int i = firstDiff; i < firstDiff + newChangeCount; i++) {
            result.add("+" + updatedLines.get(i));
        }
        for (int i = 0; i < contextAfter; i++) {
            result.add(" " + originalLines.get(oldAfterStart + i));
        }

        return result;
    }

    private static List<String> splitLines(String content) {
        return List.of(content.split("\n", -1));
    }

    private static String formatRangeStart(int zeroBasedStart, int count) {
        int oneBasedStart = count == 0 ? zeroBasedStart : zeroBasedStart + 1;
        return oneBasedStart + "," + count;
    }
}
