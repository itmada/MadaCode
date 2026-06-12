package madacode.render.tool;

import madacode.tui.theme.Tk;
import madacode.tui.TerminalText;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ToolDisplaySupport {

    private static final Pattern MAVEN_TESTS =
            Pattern.compile("Tests run: (\\d+), Failures: (\\d+), Errors: (\\d+)");
    private static final Pattern LINE_CHANGES =
            Pattern.compile("Line changes:\\s*\\+(\\d+)\\s+-(\\d+)");
    private static final Pattern EXIT_CODE =
            Pattern.compile("(?m)^Exit code:\\s*(\\d+)\\s*$");

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

    static String durationText(long durationMs) {
        return duration(durationMs);
    }

    static String withDuration(String summary, long durationMs) {
        String timing = durationText(durationMs);
        return timing.isBlank() ? summary : summary + " · " + timing;
    }

    static String byteSize(String value) {
        int bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
    }

    static String completedSummary(boolean success, long durationMs) {
        String timing = duration(durationMs);
        if (timing.isBlank()) {
            return success ? "passed" : "failed";
        }
        return (success ? "passed" : "failed") + " · " + timing;
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
        if (success) {
            return withDuration("ok", durationMs);
        }
        String exit = exitCode(output);
        String status = exit.isBlank() ? "failed" : Tk.failure("exit " + exit);
        return withDuration(status, durationMs);
    }

    static String exitCode(String output) {
        Matcher matcher = EXIT_CODE.matcher(output == null ? "" : output);
        String last = "";
        while (matcher.find()) {
            last = matcher.group(1);
        }
        return last;
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

    static String lineChangeSummary(String output) {
        Matcher matcher = LINE_CHANGES.matcher(output == null ? "" : output);
        if (!matcher.find()) {
            return "";
        }
        return Tk.diffAdd("+" + matcher.group(1)) + " " + Tk.diffDel("-" + matcher.group(2));
    }

    static String grepSummary(ObjectNode input, String output, long durationMs) {
        String mode = text(input, "outputMode");
        int context = input == null || !input.path("context").canConvertToInt()
                ? 0
                : input.path("context").asInt(0);
        List<String> lines = resultLines(output);
        if (lines.isEmpty()) {
            return withDuration("0 matches in 0 files", durationMs);
        }
        if ("count".equals(mode)) {
            int matches = 0;
            int files = 0;
            for (String line : lines) {
                int idx = line.lastIndexOf(':');
                if (idx <= 0 || idx == line.length() - 1) {
                    return "";
                }
                try {
                    matches += Integer.parseInt(line.substring(idx + 1).strip());
                    files++;
                } catch (NumberFormatException exception) {
                    return "";
                }
            }
            return withDuration(matches + " matches in " + files + " files", durationMs);
        }
        if ("content".equals(mode)) {
            if (context > 0) {
                return "";
            }
            Set<String> files = new LinkedHashSet<>();
            for (String line : lines) {
                int first = line.indexOf(':');
                int second = first < 0 ? -1 : line.indexOf(':', first + 1);
                if (first <= 0 || second <= first + 1) {
                    return "";
                }
                files.add(line.substring(0, first));
            }
            return withDuration(lines.size() + " matches in " + files.size() + " files", durationMs);
        }
        return withDuration(lines.size() + " matches in " + lines.size() + " files", durationMs);
    }

    private static List<String> resultLines(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String raw : output.split("\\R")) {
            String line = raw.strip();
            if (!line.isBlank() && !line.startsWith("[Results truncated at ")) {
                lines.add(line);
            }
        }
        return lines;
    }
}
