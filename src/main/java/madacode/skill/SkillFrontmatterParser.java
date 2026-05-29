package madacode.skill;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal YAML frontmatter parser for Mada-style markdown files
 * (used by SKILL.md and agent .md files).
 *
 * <p>Supports the subset needed for skill/agent metadata:
 * <ul>
 *   <li>{@code key: value} — single-line string</li>
 *   <li>{@code key: [a, b, c]} — inline array</li>
 *   <li>{@code key: |} — multi-line literal block (indented)</li>
 * </ul>
 *
 * <p>Does NOT depend on SnakeYAML or any third-party YAML library.
 */
public final class SkillFrontmatterParser {

    private SkillFrontmatterParser() {}

    public record Result(Map<String, Object> frontmatter, String body, List<String> warnings) {
        public Result {
            frontmatter = Collections.unmodifiableMap(new LinkedHashMap<>(frontmatter));
            warnings = List.copyOf(warnings);
        }
    }

    public static Result parse(String fullText) {
        if (fullText == null || fullText.isBlank()) {
            return new Result(Map.of(), "", List.of("empty input"));
        }

        String normalized = fullText.replace("\r\n", "\n").replace("\r", "\n");
        if (!normalized.startsWith("---\n")) {
            return new Result(Map.of(), normalized, List.of("missing frontmatter opening ---"));
        }

        int endIdx = findFrontmatterEnd(normalized);
        if (endIdx < 0) {
            return new Result(Map.of(), normalized, List.of("missing frontmatter closing ---"));
        }

        String fmText = normalized.substring(4, endIdx);
        String body = normalized.substring(endIdx + 4);
        if (body.startsWith("\n")) body = body.substring(1);

        List<String> warnings = new ArrayList<>();
        Map<String, Object> fm = parseFrontmatter(fmText, warnings);
        return new Result(fm, body, warnings);
    }

    private static int findFrontmatterEnd(String text) {
        int idx = 4; // skip first "---\n"
        while (idx < text.length()) {
            int lineEnd = text.indexOf('\n', idx);
            if (lineEnd < 0) return -1;
            if (text.substring(idx, lineEnd).equals("---")) return idx;
            idx = lineEnd + 1;
        }
        return -1;
    }

    private static Map<String, Object> parseFrontmatter(String text, List<String> warnings) {
        Map<String, Object> result = new LinkedHashMap<>();
        String[] lines = text.split("\n", -1);
        int i = 0;

        while (i < lines.length) {
            String line = lines[i];
            if (line.isBlank() || line.strip().startsWith("#")) {
                i++;
                continue;
            }

            int colonIdx = findKeyColonIndex(line);
            if (colonIdx < 0) {
                warnings.add("unparseable line: " + line);
                i++;
                continue;
            }

            String key = line.substring(0, colonIdx).trim();
            String rawValue = line.substring(colonIdx + 1).trim();

            if (key.isEmpty()) {
                i++;
                continue;
            }
            if (rawValue.isEmpty()) {
                i++;
                continue;
            }

            // array: [a, b, c]
            if (rawValue.startsWith("[") && rawValue.endsWith("]")) {
                String inner = rawValue.substring(1, rawValue.length() - 1).trim();
                if (inner.isEmpty()) {
                    result.put(key, List.of());
                } else {
                    List<String> items = new ArrayList<>();
                    for (String part : inner.split(",")) {
                        String t = unquote(part.trim());
                        if (!t.isEmpty()) items.add(t);
                    }
                    result.put(key, items);
                }
                i++;
                continue;
            }

            // multi-line literal: |
            if (rawValue.equals("|")) {
                StringBuilder sb = new StringBuilder();
                i++;
                Integer baseIndent = null;
                while (i < lines.length) {
                    String next = lines[i];
                    if (next.isBlank()) { sb.append('\n'); i++; continue; }
                    int indent = leadingSpaces(next);
                    if (indent == 0) break;
                    if (baseIndent == null) baseIndent = indent;
                    if (indent < baseIndent) break;
                    sb.append(next.substring(baseIndent)).append('\n');
                    i++;
                }
                result.put(key, sb.toString().stripTrailing());
                continue;
            }

            // simple string
            result.put(key, unquote(rawValue));
            i++;
        }
        return result;
    }

    private static int findKeyColonIndex(String line) {
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == ':' && !inSingle && !inDouble) return i;
        }
        return -1;
    }

    private static int leadingSpaces(String s) {
        int i = 0;
        while (i < s.length() && s.charAt(i) == ' ') i++;
        return i;
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            if (s.startsWith("\"") && s.endsWith("\"")) return s.substring(1, s.length() - 1);
            if (s.startsWith("'") && s.endsWith("'")) return s.substring(1, s.length() - 1);
        }
        return s;
    }

    // ---- typed accessors for callers -----------------------------------

    /** Reads a string-typed frontmatter field. Returns null if missing or wrong type. */
    public static String stringField(Map<String, Object> fm, String key) {
        Object v = fm.get(key);
        return v instanceof String s ? s : null;
    }

    /** Reads a list-of-string frontmatter field. Returns empty list if missing or wrong type. */
    public static List<String> stringListField(Map<String, Object> fm, String key) {
        Object v = fm.get(key);
        if (v instanceof List<?> list) {
            return list.stream().filter(x -> x instanceof String).map(x -> (String) x).toList();
        }
        return List.of();
    }

    /** Reads an int-typed frontmatter field. Returns defaultValue if missing or unparseable. */
    public static int intField(Map<String, Object> fm, String key, int defaultValue) {
        Object v = fm.get(key);
        if (v instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }
}
