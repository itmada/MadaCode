package madacode.permission.preview;

import madacode.render.DiffHighlighter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ApprovalInputPreview {

    private static final int MAX_LINES = 12;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApprovalInputPreview() {}

    public static List<String> render(String toolName, String input) {
        String tool = toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        JsonNode json = parse(input);
        if (tool.contains("bash")) {
            return bash(json, input);
        }
        if (tool.contains("edit") || tool.contains("write")) {
            List<String> diff = diff(tool, json);
            if (!diff.isEmpty()) return diff;
        }
        return prettyJson(json, input);
    }

    private static List<String> bash(JsonNode json, String input) {
        String command = text(json, "command");
        if (command.isBlank()) command = input == null ? "" : input.strip();
        if (command.isBlank()) return List.of("(no command)");
        List<String> out = new ArrayList<>();
        out.add(command);
        for (String part : command.split("\\s*(?:&&|\\|)\\s*")) {
            String line = part.strip();
            if (!line.isBlank() && !line.equals(command) && out.size() < MAX_LINES) {
                out.add("  " + line);
            }
        }
        return out.stream().limit(MAX_LINES).toList();
    }

    private static List<String> diff(String tool, JsonNode json) {
        String file = firstText(json, "file_path", "path");
        String rawDiff;
        if (tool.contains("edit")) {
            String oldString = text(json, "old_string");
            String newString = text(json, "new_string");
            if (oldString.isBlank() && newString.isBlank()) return List.of();
            rawDiff = unified(file, oldString, newString);
        } else {
            String content = text(json, "content");
            if (content.isBlank()) return List.of();
            rawDiff = unified(file, "", content);
        }
        return DiffHighlighter.highlightAndRender(rawDiff, MAX_LINES);
    }

    private static List<String> prettyJson(JsonNode json, String input) {
        if (json == null || json.isMissingNode()) {
            return split(input == null ? "" : input.strip(), MAX_LINES);
        }
        try {
            return split(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(json), MAX_LINES);
        } catch (Exception e) {
            return split(input == null ? "" : input.strip(), MAX_LINES);
        }
    }

    private static JsonNode parse(String input) {
        if (input == null || input.isBlank()) return MAPPER.missingNode();
        try {
            return MAPPER.readTree(input);
        } catch (Exception e) {
            return MAPPER.missingNode();
        }
    }

    private static String unified(String file, String oldString, String newString) {
        String path = file == null || file.isBlank() ? "input" : file;
        StringBuilder b = new StringBuilder();
        b.append("--- ").append(path).append('\n');
        b.append("+++ ").append(path).append('\n');
        b.append("@@ preview @@\n");
        for (String line : split(oldString, MAX_LINES)) {
            b.append('-').append(line).append('\n');
        }
        for (String line : split(newString, MAX_LINES)) {
            b.append('+').append(line).append('\n');
        }
        return b.toString();
    }

    private static List<String> split(String value, int maxLines) {
        if (value == null || value.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : value.split("\\R", -1)) {
            if (out.size() >= maxLines) break;
            out.add(line);
        }
        return List.copyOf(out);
    }

    private static String firstText(JsonNode json, String... names) {
        for (String name : names) {
            String value = text(json, name);
            if (!value.isBlank()) return value;
        }
        return "";
    }

    private static String text(JsonNode json, String field) {
        if (json == null || json.isMissingNode()) return "";
        JsonNode value = json.get(field);
        return value == null || value.isNull() ? "" : value.asText("");
    }
}
