package madacode.permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class BashCommandClassifier {

    private static final Set<String> READ_COMMANDS = Set.of(
            "ls", "cat", "head", "tail", "less", "more",
            "grep", "rg", "find", "pwd", "wc", "stat", "file",
            "du", "df", "sort", "uniq", "cut",
            "which", "printenv", "git");

    private static final Set<String> READ_ONLY_GIT = Set.of(
            "status", "log", "show", "diff", "rev-parse",
            "ls-files", "grep", "describe", "blame");

    private BashCommandClassifier() {}

    static boolean isBasicReadOnly(String command) {
        String normalized = command == null ? "" : command.strip();
        if (normalized.isBlank()) {
            return true;
        }
        List<List<String>> commands = splitCommands(normalized);
        if (commands.isEmpty()) {
            return true;
        }
        for (List<String> tokens : commands) {
            if (tokens.isEmpty()) {
                continue;
            }
            if (hasRedirection(tokens)) {
                return false;
            }
            String commandName = commandName(tokens);
            if (commandName == null || !READ_COMMANDS.contains(commandName)) {
                return false;
            }
            if ("git".equals(commandName) && !isReadOnlyGit(tokens)) {
                return false;
            }
            if ("git".equals(commandName) && hasGitOutputOption(tokens)) {
                return false;
            }
            if ("find".equals(commandName) && isFindMutation(tokens)) {
                return false;
            }
            if (isInPlaceMutation(commandName, tokens)) {
                return false;
            }
        }
        return true;
    }

    private static List<List<String>> splitCommands(String command) {
        List<String> tokens = tokenize(command);
        List<List<String>> commands = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String token : tokens) {
            if (isCommandSeparator(token)) {
                if (!current.isEmpty()) {
                    commands.add(List.copyOf(current));
                    current.clear();
                }
            } else {
                current.add(token);
            }
        }
        if (!current.isEmpty()) {
            commands.add(List.copyOf(current));
        }
        return commands;
    }

    private static List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                } else {
                    token.append(ch);
                }
                continue;
            }
            if (ch == '\'' || ch == '"') {
                quote = ch;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                flushToken(tokens, token);
                continue;
            }
            if (ch == '&' && i + 1 < command.length() && command.charAt(i + 1) == '&') {
                flushToken(tokens, token);
                tokens.add("&&");
                i++;
                continue;
            }
            if (ch == '|' && i + 1 < command.length() && command.charAt(i + 1) == '|') {
                flushToken(tokens, token);
                tokens.add("||");
                i++;
                continue;
            }
            if (ch == '>' || ch == '<') {
                flushToken(tokens, token);
                if (i + 1 < command.length() && command.charAt(i + 1) == ch) {
                    tokens.add(String.valueOf(ch) + ch);
                    i++;
                } else {
                    tokens.add(String.valueOf(ch));
                }
                continue;
            }
            if (ch == ';' || ch == '|') {
                flushToken(tokens, token);
                tokens.add(String.valueOf(ch));
                continue;
            }
            token.append(ch);
        }
        flushToken(tokens, token);
        return tokens;
    }

    private static void flushToken(List<String> tokens, StringBuilder token) {
        if (!token.isEmpty()) {
            tokens.add(token.toString());
            token.setLength(0);
        }
    }

    private static boolean isCommandSeparator(String token) {
        return ";".equals(token) || "&&".equals(token) || "||".equals(token) || "|".equals(token);
    }

    private static String commandName(List<String> tokens) {
        for (String token : tokens) {
            if (isAssignment(token) || isRedirection(token)) {
                continue;
            }
            int slash = token.lastIndexOf('/');
            String baseName = slash >= 0 ? token.substring(slash + 1) : token;
            return baseName.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static boolean isAssignment(String token) {
        int equals = token.indexOf('=');
        if (equals <= 0) {
            return false;
        }
        String name = token.substring(0, equals);
        return name.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_')
                && !Character.isDigit(name.charAt(0));
    }

    private static boolean hasRedirection(List<String> tokens) {
        return tokens.stream().anyMatch(token -> isRedirection(token) || isInlineRedirection(token));
    }

    private static boolean isRedirection(String token) {
        return ">".equals(token) || ">>".equals(token) || "<".equals(token) || "<<".equals(token);
    }

    private static boolean isInlineRedirection(String token) {
        return token.matches("\\d?>.+") || token.matches("\\d?>>.+");
    }

    private static boolean isFindMutation(List<String> tokens) {
        for (String token : tokens) {
            String normalized = token.toLowerCase(Locale.ROOT);
            if ("-delete".equals(normalized) || "-exec".equals(normalized) || "-execdir".equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInPlaceMutation(String commandName, List<String> tokens) {
        if ("sed".equals(commandName) || "perl".equals(commandName)) {
            return tokens.stream().anyMatch(token -> token.equals("-i") || token.startsWith("-i."));
        }
        if ("sort".equals(commandName)) {
            return tokens.stream().anyMatch("-o"::equals);
        }
        return false;
    }

    private static boolean isReadOnlyGit(List<String> tokens) {
        String subcommand = null;
        for (int i = 1; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if ("-C".equals(token)) {
                i++;
                continue;
            }
            if (token.startsWith("-")) {
                continue;
            }
            subcommand = token.toLowerCase(Locale.ROOT);
            break;
        }
        return subcommand != null && READ_ONLY_GIT.contains(subcommand);
    }

    private static boolean hasGitOutputOption(List<String> tokens) {
        for (String token : tokens) {
            if ("--output".equals(token) || token.startsWith("--output=")) {
                return true;
            }
        }
        return false;
    }
}
