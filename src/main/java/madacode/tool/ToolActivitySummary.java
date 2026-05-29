package madacode.tool;

import madacode.tui.TerminalText;

import com.fasterxml.jackson.databind.node.ObjectNode;

public final class ToolActivitySummary {

    private static final String PREFIX = "▸ ";
    private static final int MAX_FIELD_COLUMNS = 72;
    private static final int MAX_PATTERN_COLUMNS = 48;
    private static final int MAX_SUMMARY_COLUMNS = 120;

    private ToolActivitySummary() {}

    public static String summarize(String toolName, ObjectNode input) {
        String normalized = normalize(toolName);
        String summary = switch (normalized) {
            case "file_read", "read" -> "Reading " + field(input, "path");
            case "write", "file_write" -> "Writing " + field(input, "file_path");
            case "edit", "file_edit" -> "Editing " + field(input, "file_path");
            case "bash" -> "Running " + firstNonBlank(
                    field(input, "description"), field(input, "command"));
            case "grep" -> "Searching for \"" + field(input, "pattern", MAX_PATTERN_COLUMNS) + "\"";
            case "glob" -> "Finding " + field(input, "pattern", MAX_PATTERN_COLUMNS);
            case "web_fetch", "webfetch" -> "Fetching " + field(input, "url");
            case "agent" -> "Agent(" + firstNonBlank(field(input, "subagent_type"), "default") + "): "
                    + firstNonBlank(field(input, "description"), field(input, "prompt"));
            case "skill" -> "Skill(" + firstNonBlank(field(input, "skill"), "unknown") + "): "
                    + firstNonBlank(field(input, "task"), "Running");
            default -> "Running " + firstNonBlank(clean(normalized), "tool");
        };
        return fit(summary, MAX_SUMMARY_COLUMNS);
    }

    public static String asProjectionLine(String toolName, ObjectNode input) {
        return PREFIX + summarize(toolName, input);
    }

    private static String normalize(String toolName) {
        return toolName == null ? "" : toolName.strip().toLowerCase();
    }

    private static String field(ObjectNode input, String field) {
        return field(input, field, MAX_FIELD_COLUMNS);
    }

    private static String field(ObjectNode input, String field, int maxColumns) {
        String value = input == null ? "" : input.path(field).asText("");
        return fit(value, maxColumns);
    }

    private static String fit(String value, int maxColumns) {
        return TerminalText.fitEnd(clean(value), maxColumns);
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').strip();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }
}
