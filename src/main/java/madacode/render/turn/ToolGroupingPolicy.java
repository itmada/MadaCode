package madacode.render.turn;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.tool.ToolNames;
import madacode.tui.TerminalText;
import madacode.util.ToolNameNormalizer;

import java.util.List;
import java.util.Locale;
import java.util.Set;

final class ToolGroupingPolicy {

    private static final Set<String> READ_ONLY_COMMANDS = Set.of(
            "pwd", "ls", "find", "rg", "grep", "cat", "sed", "awk",
            "head", "tail", "wc", "nl", "tree", "sort");

    private static final Set<String> READ_ONLY_PIPE_COMMANDS = Set.of(
            "rg", "grep", "sed", "awk", "head", "tail", "wc", "nl", "sort", "cat");

    private static final Set<String> READ_ONLY_GIT_SUBCOMMANDS = Set.of(
            "status", "diff", "grep", "show", "log");

    ToolActivityDescriptor describe(String toolName, ObjectNode input) {
        String normalized = normalize(toolName);
        return switch (normalized) {
            case "file_read", "read" -> groupable(
                    ToolActivityKind.READ, "Read", field(input, "path", "file_path"));
            case "glob", "list" -> groupable(
                    ToolActivityKind.LIST, "List", quote(field(input, "pattern", "")));
            case "grep", "search" -> groupable(
                    ToolActivityKind.SEARCH, "Search", grepTarget(input));
            case "bash" -> describeBash(field(input, "command", ""));
            case "write", "file_write" -> standalone(ToolActivityKind.WRITE, "Write", field(input, "file_path", "path"));
            case "edit", "file_edit" -> standalone(ToolActivityKind.EDIT, "Edit", field(input, "file_path", "path"));
            case "agent" -> standalone(ToolActivityKind.AGENT, "Agent", field(input, "subagent_type", ""));
            case "skill" -> standalone(ToolActivityKind.AGENT, "Skill", field(input, "skill", ""));
            case "update_plan" -> new ToolActivityDescriptor(
                    ToolActivityKind.PLAN, ToolActivityGrouping.NEVER_GROUP, "Plan", "");
            default -> standalone(ToolActivityKind.UNKNOWN, title(normalized), "");
        };
    }

    private static ToolActivityDescriptor describeBash(String command) {
        if (command.isBlank() || !isReadOnlyShell(command)) {
            return standalone(ToolActivityKind.EXEC, "Run", command);
        }
        String first = firstToken(firstPipelineSegment(command));
        if ("git".equals(first)) {
            String subcommand = gitSubcommand(firstPipelineSegment(command));
            ToolActivityKind kind = "grep".equals(subcommand)
                    ? ToolActivityKind.SEARCH
                    : ToolActivityKind.INSPECT;
            return groupable(kind, "Inspect", command);
        }
        ToolActivityKind kind = switch (first) {
            case "ls", "find", "tree" -> ToolActivityKind.LIST;
            case "rg", "grep" -> ToolActivityKind.SEARCH;
            default -> ToolActivityKind.READ;
        };
        String action = switch (kind) {
            case LIST -> "List";
            case SEARCH -> "Search";
            case INSPECT -> "Inspect";
            default -> "Read";
        };
        return groupable(kind, action, command);
    }

    private static boolean isReadOnlyShell(String command) {
        String clean = command.strip();
        if (clean.isEmpty() || containsUnsafeShellSyntax(clean)) {
            return false;
        }
        List<String> segments = List.of(clean.split("\\|"));
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i).strip();
            if (segment.isBlank()) {
                return false;
            }
            String first = firstToken(segment);
            if ("git".equals(first)) {
                if (!READ_ONLY_GIT_SUBCOMMANDS.contains(gitSubcommand(segment))
                        || containsGitOutputFlag(segment)) {
                    return false;
                }
                continue;
            }
            Set<String> allowed = i == 0 ? READ_ONLY_COMMANDS : READ_ONLY_PIPE_COMMANDS;
            if (!allowed.contains(first) || containsMutatingReadOnlyFlag(first, segment)) {
                return false;
            }
        }
        return true;
    }

    private static boolean containsUnsafeShellSyntax(String command) {
        return command.contains("&&")
                || command.contains("||")
                || command.contains(";")
                || command.contains(">")
                || command.contains("<")
                || command.contains("`")
                || command.contains("$(")
                || command.contains("\n")
                || command.contains("\r");
    }

    private static boolean containsMutatingReadOnlyFlag(String command, String segment) {
        List<String> tokens = tokens(segment);
        return switch (command) {
            case "sed" -> tokens.stream().anyMatch(token ->
                    token.equals("-i") || token.startsWith("-i."));
            case "find" -> tokens.stream().anyMatch(token ->
                    token.equals("-delete")
                            || token.equals("-exec")
                            || token.equals("-execdir")
                            || token.equals("-ok")
                            || token.equals("-okdir"));
            case "sort" -> tokens.stream().anyMatch(token ->
                    token.equals("-o") || token.startsWith("-o"));
            default -> false;
        };
    }

    private static boolean containsGitOutputFlag(String segment) {
        return tokens(segment).stream().anyMatch(token ->
                token.equals("--output") || token.startsWith("--output="));
    }

    private static List<String> tokens(String segment) {
        return List.of(segment.strip().split("\\s+"));
    }

    private static ToolActivityDescriptor groupable(
            ToolActivityKind kind, String action, String target) {
        return new ToolActivityDescriptor(
                kind,
                ToolActivityGrouping.GROUPABLE_EXPLORATION,
                action,
                truncate(target));
    }

    private static ToolActivityDescriptor standalone(
            ToolActivityKind kind, String action, String target) {
        return new ToolActivityDescriptor(
                kind,
                ToolActivityGrouping.STANDALONE,
                action,
                truncate(target));
    }

    private static String grepTarget(ObjectNode input) {
        String pattern = quote(field(input, "pattern", ""));
        String path = field(input, "path", "");
        return path.isBlank() ? pattern : pattern + " in " + path;
    }

    private static String field(ObjectNode input, String preferred, String fallback) {
        if (input == null) {
            return "";
        }
        String value = input.path(preferred).asText("");
        if (value.isBlank() && !fallback.isBlank()) {
            value = input.path(fallback).asText("");
        }
        return value;
    }

    private static String firstPipelineSegment(String command) {
        int pipe = command.indexOf('|');
        return pipe < 0 ? command.strip() : command.substring(0, pipe).strip();
    }

    private static String firstToken(String command) {
        String clean = command.strip();
        if (clean.isEmpty()) {
            return "";
        }
        int idx = 0;
        while (idx < clean.length() && !Character.isWhitespace(clean.charAt(idx))) {
            idx++;
        }
        return clean.substring(0, idx).toLowerCase(Locale.ROOT);
    }

    private static String gitSubcommand(String command) {
        String clean = command.strip();
        if (!firstToken(clean).equals("git")) {
            return "";
        }
        String rest = clean.substring(Math.min(clean.length(), 3)).strip();
        return firstToken(rest);
    }

    private static String normalize(String toolName) {
        String normalized = ToolNameNormalizer.normalize(toolName);
        if (normalized == null || normalized.isBlank()) {
            return toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
        }
        if (ToolNames.FILE_READ.equals(normalized)) {
            return "file_read";
        }
        return normalized;
    }

    private static String quote(String value) {
        return "\"" + truncate(value) + "\"";
    }

    private static String truncate(String value) {
        return TerminalText.truncateMiddle(value == null ? "" : value, 88);
    }

    private static String title(String value) {
        if (value == null || value.isBlank()) {
            return "Tool";
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
