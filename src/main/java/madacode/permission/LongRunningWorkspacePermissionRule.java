package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.tool.Tool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class LongRunningWorkspacePermissionRule implements PermissionRule {

    public static final String SOURCE = "long_running_workspace";
    private static final Set<String> WORKSPACE_READ_TOOLS = Set.of("file_read", "glob", "grep");

    @Override
    public Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        if (!applies(context)) {
            return Optional.empty();
        }

        if (tool.isReadOnly()) {
            if (!WORKSPACE_READ_TOOLS.contains(tool.name())) {
                return Optional.of(PermissionDecision.deny(
                        "Long-running workers may only use workspace-scoped read tools.",
                        SOURCE));
            }
            Path workingDir = context.workingDirectory();
            for (String target : tool.permissionTargets(input)) {
                if (!FilesystemScope.withinRoots(target, workingDir, List.of())) {
                    return Optional.of(PermissionDecision.deny(
                            "Long-running worker reads must stay inside the workspace.",
                            SOURCE));
                }
            }
            return Optional.of(PermissionDecision.allow(SOURCE));
        }

        if ("bash".equals(tool.name())) {
            return Optional.of(evaluateBash(input.path("command").asText(""), context.workingDirectory()));
        }

        if (isWorkerTaskStoreTool(tool)) {
            return Optional.of(PermissionDecision.allow(SOURCE));
        }

        if (tool.isFileEdit()) {
            List<String> targets = tool.permissionTargets(input);
            if (targets.isEmpty()) {
                return Optional.of(PermissionDecision.deny(
                        "Long-running worker file edits must declare a workspace target.",
                        SOURCE));
            }

            Path workingDir = context.workingDirectory();
            for (String target : targets) {
                if (!FilesystemScope.withinRoots(target, workingDir, List.of())) {
                    return Optional.of(PermissionDecision.deny(
                            "Long-running worker file edits must stay inside the workspace.",
                            SOURCE));
                }
                if (FilesystemScope.isDangerousEditTarget(target, workingDir)) {
                    return Optional.of(PermissionDecision.deny(
                            "Long-running workers cannot modify sensitive workspace metadata.",
                            SOURCE));
                }
            }

            return Optional.of(PermissionDecision.allow(SOURCE));
        }

        return Optional.of(PermissionDecision.deny(
                "Long-running worker cannot request interactive approval for tool: " + tool.name(),
                SOURCE));
    }

    private static boolean applies(ToolUseContext context) {
        return context.session().isLongRunningWorkerSession()
                && context.session().permissionMode() == PermissionMode.LONG_RUNNING_WORKSPACE;
    }

    private static boolean isWorkerTaskStoreTool(Tool<?> tool) {
        return "longrun_task_update".equals(tool.name())
                || "worker_report".equals(tool.name());
    }

    private static PermissionDecision evaluateBash(String command, Path workingDir) {
        BashScopeDecision decision = BashScopeDecision.evaluate(command, workingDir);
        if (decision.allowed()) {
            return PermissionDecision.allow(SOURCE);
        }
        return PermissionDecision.deny(decision.reason(), SOURCE);
    }

    private record BashScopeDecision(boolean allowed, String reason) {
        static BashScopeDecision allow() {
            return new BashScopeDecision(true, "");
        }

        static BashScopeDecision deny(String reason) {
            return new BashScopeDecision(false, reason);
        }

        static BashScopeDecision evaluate(String command, Path workingDir) {
            String normalized = command == null ? "" : command.strip();
            if (normalized.isBlank()) {
                return allow();
            }

            List<List<String>> commands = splitCommands(normalized);
            if (commands.isEmpty()) {
                return allow();
            }

            Path currentDir = workingDir.toAbsolutePath().normalize();
            for (List<String> commandTokens : commands) {
                if (commandTokens.isEmpty()) {
                    continue;
                }
                String commandName = commandName(commandTokens);
                if (commandName == null) {
                    continue;
                }
                if ("cd".equals(commandName)) {
                    String target = firstNonOptionArgument(commandTokens, 1);
                    if (target == null) {
                        currentDir = workingDir.toAbsolutePath().normalize();
                        continue;
                    }
                    if (containsExpansion(target)) {
                        return deny("Long-running worker bash cannot use unresolved shell expansion to change directories.");
                    }
                    currentDir = resolvePath(target, currentDir);
                    continue;
                }

                boolean currentOutsideWorkspace = !insideWorkspace(currentDir.toString(), workingDir);
                Path effectiveDir = currentDir;
                Path gitWorkTree = gitWorkTree(commandTokens, currentDir);
                if (gitWorkTree != null) {
                    effectiveDir = gitWorkTree;
                }

                ExternalUse externalUse = externalUse(commandTokens, effectiveDir, workingDir);
                if (externalUse.externalWrite()) {
                    return deny("Long-running worker bash cannot modify files outside the workspace.");
                }
                if ((currentOutsideWorkspace || externalUse.externalRead())
                        && !isAllowedExternalReadCommand(commandName, commandTokens)) {
                    return deny("Long-running worker bash outside the workspace is limited to read-only inspection commands.");
                }
                if (currentOutsideWorkspace && hasRedirection(commandTokens)) {
                    return deny("Long-running worker bash cannot redirect output while running outside the workspace.");
                }
                if (mayMutateWithUnresolvedExpansion(commandName, commandTokens)) {
                    return deny("Long-running worker bash cannot use unresolved shell expansion in mutating commands.");
                }
            }
            return allow();
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
                    if (i + 1 < command.length() && command.charAt(i + 1) == '>') {
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
                return baseName(token).toLowerCase(Locale.ROOT);
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

        private static String firstNonOptionArgument(List<String> tokens, int from) {
            for (int i = from; i < tokens.size(); i++) {
                String token = tokens.get(i);
                if (isRedirection(token)) {
                    i++;
                    continue;
                }
                if (!token.startsWith("-")) {
                    return token;
                }
            }
            return null;
        }

        private static Path gitWorkTree(List<String> tokens, Path currentDir) {
            String commandName = commandName(tokens);
            if (!"git".equals(commandName)) {
                return null;
            }
            for (int i = 0; i < tokens.size() - 1; i++) {
                if ("-C".equals(tokens.get(i))) {
                    return resolvePath(tokens.get(i + 1), currentDir);
                }
            }
            return null;
        }

        private static ExternalUse externalUse(List<String> tokens, Path currentDir, Path workingDir) {
            boolean externalRead = false;
            boolean externalWrite = false;
            String commandName = commandName(tokens);
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                if (isCommandSeparator(token) || isAssignment(token)) {
                    continue;
                }
                if (isRedirection(token)) {
                    String target = i + 1 < tokens.size() ? tokens.get(i + 1) : "";
                    if (isPathLike(target) && !insideWorkspace(resolvePath(target, currentDir).toString(), workingDir)) {
                        externalWrite = true;
                    }
                    i++;
                    continue;
                }
                if (isInlineRedirection(token)) {
                    String target = token.substring(token.indexOf('>') + 1);
                    if (isPathLike(target) && !insideWorkspace(resolvePath(target, currentDir).toString(), workingDir)) {
                        externalWrite = true;
                    }
                    continue;
                }
                if (!isPathLike(token)) {
                    continue;
                }
                Path path = resolvePath(token, currentDir);
                if (!insideWorkspace(path.toString(), workingDir)) {
                    if (isMutatingCommand(commandName, tokens)) {
                        externalWrite = true;
                    } else {
                        externalRead = true;
                    }
                }
            }
            if (isFindMutation(tokens) || isInPlaceMutation(commandName, tokens)) {
                externalWrite = externalRead || externalWrite;
            }
            return new ExternalUse(externalRead, externalWrite);
        }

        private record ExternalUse(boolean externalRead, boolean externalWrite) {}

        private static boolean isAllowedExternalReadCommand(String commandName, List<String> tokens) {
            if ("git".equals(commandName)) {
                return isReadOnlyGit(tokens);
            }
            if ("find".equals(commandName) && isFindMutation(tokens)) {
                return false;
            }
            if (isInPlaceMutation(commandName, tokens)) {
                return false;
            }
            return Set.of(
                    "ls", "cat", "head", "tail", "less", "more",
                    "grep", "rg", "find", "pwd", "wc", "stat", "file",
                    "du", "df", "sort", "uniq", "cut", "awk", "sed",
                    "git").contains(commandName);
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
            if (subcommand == null) {
                return false;
            }
            return Set.of(
                    "status", "log", "show", "diff", "branch", "rev-parse",
                    "ls-files", "grep", "remote", "config", "describe",
                    "tag", "blame").contains(subcommand);
        }

        private static boolean isKnownMutatingCommand(String commandName) {
            return Set.of(
                    "rm", "rmdir", "mv", "cp", "mkdir", "touch", "chmod", "chown",
                    "ln", "tee", "truncate", "install", "patch", "git").contains(commandName);
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

        private static boolean hasRedirection(List<String> tokens) {
            return tokens.stream().anyMatch(token -> isRedirection(token) || isInlineRedirection(token));
        }

        private static boolean mayMutateWithUnresolvedExpansion(String commandName, List<String> tokens) {
            if (!isMutatingCommand(commandName, tokens)) {
                return false;
            }
            return tokens.stream().anyMatch(BashScopeDecision::containsExpansion);
        }

        private static boolean isMutatingCommand(String commandName, List<String> tokens) {
            if ("git".equals(commandName)) {
                return !isReadOnlyGit(tokens);
            }
            return isKnownMutatingCommand(commandName);
        }

        private static boolean isRedirection(String token) {
            return ">".equals(token) || ">>".equals(token) || "<".equals(token) || "<<".equals(token);
        }

        private static boolean isInlineRedirection(String token) {
            return token.matches("\\d?>.+") || token.matches("\\d?>>.+");
        }

        private static boolean containsExpansion(String token) {
            return token.contains("$") || token.contains("`");
        }

        private static boolean isPathLike(String token) {
            if (token == null || token.isBlank() || token.startsWith("-")) {
                return false;
            }
            if (containsExpansion(token)) {
                return false;
            }
            return token.startsWith("/")
                    || token.startsWith(".")
                    || token.startsWith("~")
                    || token.contains("/");
        }

        private static Path resolvePath(String token, Path currentDir) {
            String normalized = token == null ? "" : token;
            if (normalized.startsWith("~/")) {
                normalized = System.getProperty("user.home") + normalized.substring(1);
            }
            try {
                Path path = Path.of(normalized);
                return path.isAbsolute()
                        ? path.normalize()
                        : currentDir.resolve(path).normalize();
            } catch (RuntimeException exception) {
                return currentDir;
            }
        }

        private static boolean insideWorkspace(String rawPath, Path workingDir) {
            return FilesystemScope.withinRoots(rawPath, workingDir, List.of());
        }

        private static String baseName(String command) {
            int slash = command.lastIndexOf('/');
            return slash >= 0 ? command.substring(slash + 1) : command;
        }
    }
}
