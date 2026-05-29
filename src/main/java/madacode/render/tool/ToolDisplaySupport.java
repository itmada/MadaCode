package madacode.render.tool;

import madacode.tui.theme.Tk;
import madacode.tui.TerminalText;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ToolDisplaySupport {

    private static final Pattern MAVEN_TESTS =
            Pattern.compile("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+)");

    private ToolDisplaySupport() {}

    static String text(ObjectNode input, String field) {
        if (input == null) {
            return "";
        }
        return input.path(field).asText("");
    }

    static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    static String quote(String value) {
        return "\"" + truncateMiddle(value == null ? "" : value, 48) + "\"";
    }

    static String parens(String value) {
        return "(" + truncateMiddle(value == null ? "" : value, 72) + ")";
    }

    static String duration(long durationMs) {
        if (durationMs < 0) {
            return "";
        }
        if (durationMs < 1000) {
            return durationMs + "ms";
        }
        double seconds = durationMs / 1000.0;
        return String.format(java.util.Locale.ROOT, "%.1fs", seconds);
    }

    static String completedSummary(boolean success, long durationMs) {
        String timing = duration(durationMs);
        if (timing.isBlank()) {
            return success ? "Completed" : "Failed";
        }
        return (success ? "Completed" : "Failed") + " · " + timing;
    }

    static int countNonBlankLines(String output) {
        if (output == null || output.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String line : output.split("\\R")) {
            if (!line.isBlank()) {
                count++;
            }
        }
        return count;
    }

    static String plural(int count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }

    /**
     * Truncate to {@code max} terminal columns, with an ellipsis in the middle
     * if shortened. CJK / wide characters count as 2 columns.
     */
    static String truncateMiddle(String value, int max) {
        if (value == null) {
            return "";
        }
        String clean = value.replace('\n', ' ').replace('\r', ' ').strip();
        if (Tk.displayWidth(clean) <= max) {
            return clean;
        }
        if (max <= 1) {
            return "…";
        }
        int budget = max - 1; // ellipsis takes 1 column
        int leftBudget = budget / 2;
        int rightBudget = budget - leftBudget;
        String head = takeFromStart(clean, leftBudget);
        String tail = takeFromEnd(clean, rightBudget);
        return head + "…" + tail;
    }

    static String fitEnd(String value, int max) {
        return TerminalText.fitEnd(value == null ? "" : value, max);
    }

    private static String takeFromStart(String s, int columns) {
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int w = org.jline.utils.WCWidth.wcwidth(cp);
            if (w < 0) w = 0;
            if (used + w > columns) break;
            sb.appendCodePoint(cp);
            used += w;
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private static String takeFromEnd(String s, int columns) {
        // Walk backwards by code point, accumulating display width.
        int[] codePoints = s.codePoints().toArray();
        int used = 0;
        int start = codePoints.length;
        for (int i = codePoints.length - 1; i >= 0; i--) {
            int w = org.jline.utils.WCWidth.wcwidth(codePoints[i]);
            if (w < 0) w = 0;
            if (used + w > columns) break;
            used += w;
            start = i;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < codePoints.length; i++) {
            sb.appendCodePoint(codePoints[i]);
        }
        return sb.toString();
    }

    static String bashSummary(String output, boolean success, long durationMs) {
        String timing = duration(durationMs);
        if (output != null && output.contains("BUILD SUCCESS")) {
            String tests = mavenTests(output);
            if (!tests.isBlank()) {
                return "BUILD SUCCESS · " + tests + (timing.isBlank() ? "" : " · " + timing);
            }
            return "BUILD SUCCESS" + (timing.isBlank() ? "" : " · " + timing);
        }
        if (output != null && output.contains("BUILD FAILURE")) {
            return "BUILD FAILURE" + (timing.isBlank() ? "" : " · " + timing);
        }
        return completedSummary(success, durationMs);
    }

    static String mavenTests(String output) {
        Matcher matcher = MAVEN_TESTS.matcher(output == null ? "" : output);
        String last = "";
        while (matcher.find()) {
            int tests = Integer.parseInt(matcher.group(1));
            int failures = Integer.parseInt(matcher.group(2));
            int errors = Integer.parseInt(matcher.group(3));
            last = tests + " tests";
            if (failures > 0 || errors > 0) {
                last += ", " + failures + " failures, " + errors + " errors";
            }
        }
        return last;
    }

    static List<String> firstUsefulLines(String output, int maxLines) {
        if (output == null || output.isBlank() || maxLines <= 0) {
            return List.of();
        }
        List<String> preferred = new ArrayList<>();
        List<String> fallback = new ArrayList<>();
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            if (line.isBlank()) {
                continue;
            }
            if (fallback.size() < maxLines) {
                fallback.add(truncateMiddle(line, 120));
            }
            String upper = line.toUpperCase(java.util.Locale.ROOT);
            if ((upper.contains("ERROR") || upper.contains("FAIL") || upper.contains("EXCEPTION"))
                    && preferred.size() < maxLines) {
                preferred.add(truncateMiddle(line, 120));
            }
        }
        return preferred.isEmpty() ? fallback : preferred;
    }

    static List<String> diffLines(String output, int maxLines) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        return madacode.render.DiffHighlighter.highlightAndRender(output, maxLines);
    }
}
