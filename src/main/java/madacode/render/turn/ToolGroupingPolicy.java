package madacode.render.turn;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.shell.BashCommandModel;
import madacode.tui.TerminalText;
import madacode.util.ToolNameNormalizer;

import java.util.Locale;

final class ToolGroupingPolicy {

    ToolActivityDescriptor describe(String toolName, ObjectNode input) {
        String normalized = normalize(toolName);
        return switch (normalized) {
            case "file_read" -> groupable(
                    ToolActivityKind.READ, "Read", field(input, "path", "file_path"));
            case "glob" -> groupable(
                    ToolActivityKind.LIST, "List", quote(field(input, "pattern", "")));
            case "grep" -> groupable(
                    ToolActivityKind.SEARCH, "Search", grepTarget(input));
            case "bash" -> describeBash(field(input, "command", ""));
            case "file_write" -> standalone(ToolActivityKind.WRITE, "Write", field(input, "file_path", "path"));
            case "file_edit" -> standalone(ToolActivityKind.EDIT, "Edit", field(input, "file_path", "path"));
            case "agent" -> standalone(ToolActivityKind.AGENT, "Agent", field(input, "subagent_type", ""));
            case "skill" -> standalone(ToolActivityKind.AGENT, "Skill", field(input, "skill", ""));
            case "update_plan" -> new ToolActivityDescriptor(
                    ToolActivityKind.PLAN, ToolActivityGrouping.NEVER_GROUP, "Plan", "");
            default -> standalone(ToolActivityKind.UNKNOWN, title(normalized), "");
        };
    }

    private static ToolActivityDescriptor describeBash(String command) {
        BashCommandModel model = BashCommandModel.parse(command);
        BashCommandModel.Segment primary = primaryExplorationSegment(model);
        if (primary == null) {
            return standalone(ToolActivityKind.EXEC, "Run", command);
        }

        ToolActivityKind kind = bashKind(primary);
        return groupable(kind, bashAction(kind), command);
    }

    private static BashCommandModel.Segment primaryExplorationSegment(BashCommandModel model) {
        if (model.isBlank() || model.connectors().contains(BashCommandModel.Connector.BACKGROUND)) {
            return null;
        }

        BashCommandModel.Segment primary = null;
        for (BashCommandModel.Segment segment : model.segments()) {
            if (!isExplorationSegment(segment)) {
                return null;
            }
            if (primary == null && !isAuxiliaryExplorationSegment(segment)) {
                primary = segment;
            }
        }
        return primary;
    }

    private static boolean isExplorationSegment(BashCommandModel.Segment segment) {
        if (segment.hasUnresolvedExpansion() || segment.writesRealFile() || segment.isMutatingCommand()) {
            return false;
        }
        return segment.isBasicReadOnlyCommand() || isUiLabelSegment(segment) || isDirectorySegment(segment);
    }

    private static boolean isAuxiliaryExplorationSegment(BashCommandModel.Segment segment) {
        return isUiLabelSegment(segment) || isDirectorySegment(segment);
    }

    private static boolean isUiLabelSegment(BashCommandModel.Segment segment) {
        return "echo".equals(segment.commandName()) || "printf".equals(segment.commandName());
    }

    private static boolean isDirectorySegment(BashCommandModel.Segment segment) {
        return "cd".equals(segment.commandName()) && segment.cdTarget() != null;
    }

    private static ToolActivityKind bashKind(BashCommandModel.Segment segment) {
        String commandName = segment.commandName();
        if ("git".equals(commandName)) {
            return "grep".equals(segment.gitSubcommand())
                    ? ToolActivityKind.SEARCH
                    : ToolActivityKind.INSPECT;
        }
        if ("rg".equals(commandName) && segment.tokens().contains("--files")) {
            return ToolActivityKind.LIST;
        }
        return switch (commandName) {
            case "ls", "find", "tree" -> ToolActivityKind.LIST;
            case "rg", "grep" -> ToolActivityKind.SEARCH;
            default -> ToolActivityKind.INSPECT;
        };
    }

    private static String bashAction(ToolActivityKind kind) {
        return switch (kind) {
            case LIST -> "List";
            case SEARCH -> "Search";
            case INSPECT -> "Inspect";
            default -> "Read";
        };
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

    private static String normalize(String toolName) {
        String normalized = ToolNameNormalizer.normalize(toolName);
        if (normalized == null || normalized.isBlank()) {
            return toolName == null ? "" : toolName.toLowerCase(Locale.ROOT);
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
