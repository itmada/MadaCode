package madacode.permission.bash;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative structural model for simple bash command lines used by
 * permission checks. It intentionally does not attempt full shell parsing.
 */
public final class BashCommandModel {

    private static final Set<String> BASIC_READ_COMMANDS = Set.of(
            "ls", "cat", "head", "tail", "less", "more",
            "grep", "rg", "find", "pwd", "wc", "stat", "file",
            "du", "df", "sort", "uniq", "cut",
            "which", "printenv", "git");

    private static final Set<String> READ_ONLY_GIT = Set.of(
            "status", "log", "show", "diff", "rev-parse",
            "ls-files", "grep", "describe", "blame");

    private static final Set<String> MUTATING_COMMANDS = Set.of(
            "rm", "rmdir", "mv", "cp", "mkdir", "touch", "chmod", "chown",
            "ln", "tee", "truncate", "install", "patch", "apply_patch");

    private static final Set<String> MUTATING_GIT = Set.of(
            "add", "am", "apply", "bisect", "checkout", "cherry-pick", "clean",
            "commit", "merge", "mv", "pull", "push", "rebase", "reset", "restore",
            "revert", "rm", "stash", "switch", "tag", "worktree");

    private static final Pattern INLINE_REDIRECTION = Pattern.compile("^(\\d?>>?)(.+)$");

    private final List<Segment> segments;
    private final List<Connector> connectors;

    private BashCommandModel(List<Segment> segments, List<Connector> connectors) {
        this.segments = List.copyOf(segments);
        this.connectors = List.copyOf(connectors);
    }

    public static BashCommandModel parse(String command) {
        String normalized = command == null ? "" : command.strip();
        if (normalized.isBlank()) {
            return new BashCommandModel(List.of(), List.of());
        }

        List<String> tokens = tokenize(normalized);
        List<Segment> segments = new ArrayList<>();
        List<Connector> connectors = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String token : tokens) {
            Connector connector = Connector.fromToken(token);
            if (connector != null) {
                if (!current.isEmpty()) {
                    segments.add(Segment.fromTokens(current));
                    connectors.add(connector);
                    current = new ArrayList<>();
                }
                continue;
            }
            current.add(token);
        }
        if (!current.isEmpty()) {
            segments.add(Segment.fromTokens(current));
        }
        if (connectors.size() >= segments.size() && !connectors.isEmpty()) {
            connectors.remove(connectors.size() - 1);
        }
        return new BashCommandModel(segments, connectors);
    }

    public boolean isBlank() {
        return segments.isEmpty();
    }

    public boolean isBasicReadOnly() {
        if (segments.isEmpty()) {
            return true;
        }
        return segments.stream().allMatch(Segment::isBasicReadOnlyCommand);
    }

    public List<Segment> segments() {
        return segments;
    }

    public List<Connector> connectors() {
        return connectors;
    }

    public enum Connector {
        PIPE("|"),
        AND_IF("&&"),
        OR_IF("||"),
        BACKGROUND("&"),
        SEQUENCE(";");

        private final String token;

        Connector(String token) {
            this.token = token;
        }

        static Connector fromToken(String token) {
            for (Connector connector : values()) {
                if (connector.token.equals(token)) {
                    return connector;
                }
            }
            return null;
        }
    }

    public record Redirection(String operator, String target, boolean inline) {}

    public record Segment(
            List<String> tokens,
            String commandName,
            String gitSubcommand,
            List<String> pathLikeArgs,
            List<Redirection> redirections,
            boolean hasRedirection,
            boolean hasUnresolvedExpansion,
            boolean inPlaceMutation,
            boolean findMutation,
            boolean hasGitOutputOption,
            String firstNonOptionArgument,
            String cdTarget,
            String gitChangeDirectoryTarget) {

        public Segment {
            tokens = List.copyOf(tokens);
            pathLikeArgs = List.copyOf(pathLikeArgs);
            redirections = List.copyOf(redirections);
        }

        static Segment fromTokens(List<String> tokens) {
            List<String> copiedTokens = List.copyOf(tokens);
            List<Redirection> redirections = new ArrayList<>();
            List<String> pathLikeArgs = new ArrayList<>();
            boolean hasRedirection = false;
            boolean hasUnresolvedExpansion = copiedTokens.stream().anyMatch(BashCommandModel::containsExpansion);

            int commandIndex = -1;
            for (int i = 0; i < copiedTokens.size(); i++) {
                String token = copiedTokens.get(i);
                if (isRedirection(token)) {
                    hasRedirection = true;
                    i++;
                    continue;
                }
                if (inlineRedirection(token) != null) {
                    hasRedirection = true;
                    continue;
                }
                if (isAssignment(token)) {
                    continue;
                }
                commandIndex = i;
                break;
            }

            String commandName = commandIndex >= 0
                    ? baseName(copiedTokens.get(commandIndex)).toLowerCase(Locale.ROOT)
                    : null;

            String gitSubcommand = null;
            String gitChangeDirectoryTarget = null;
            String firstNonOptionArgument = null;
            if (commandIndex >= 0) {
                for (int i = commandIndex + 1; i < copiedTokens.size(); i++) {
                    String token = copiedTokens.get(i);
                    if (isRedirection(token)) {
                        i++;
                        continue;
                    }
                    if (inlineRedirection(token) != null) {
                        continue;
                    }
                    if ("-C".equals(token)) {
                        if ("git".equals(commandName) && i + 1 < copiedTokens.size()) {
                            gitChangeDirectoryTarget = copiedTokens.get(i + 1);
                        }
                        i++;
                        continue;
                    }
                    if (token.startsWith("-")) {
                        continue;
                    }
                    firstNonOptionArgument = token;
                    break;
                }
            }

            if ("git".equals(commandName)) {
                for (int i = commandIndex + 1; i < copiedTokens.size(); i++) {
                    String token = copiedTokens.get(i);
                    if (isRedirection(token)) {
                        i++;
                        continue;
                    }
                    if (inlineRedirection(token) != null) {
                        continue;
                    }
                    if ("-C".equals(token)) {
                        i++;
                        continue;
                    }
                    if (token.startsWith("-")) {
                        continue;
                    }
                    gitSubcommand = token.toLowerCase(Locale.ROOT);
                    break;
                }
            }

            for (int i = 0; i < copiedTokens.size(); i++) {
                String token = copiedTokens.get(i);
                if (isAssignment(token)) {
                    continue;
                }
                if (isRedirection(token)) {
                    hasRedirection = true;
                    String target = i + 1 < copiedTokens.size() ? copiedTokens.get(i + 1) : "";
                    redirections.add(new Redirection(token, target, false));
                    i++;
                    continue;
                }
                Redirection inline = inlineRedirection(token);
                if (inline != null) {
                    hasRedirection = true;
                    redirections.add(inline);
                    continue;
                }
                if (i == commandIndex || token.startsWith("-")) {
                    continue;
                }
                if (isPathLike(token)) {
                    pathLikeArgs.add(token);
                }
            }

            boolean inPlaceMutation = isInPlaceMutation(commandName, copiedTokens);
            boolean findMutation = isFindMutation(copiedTokens);
            boolean hasGitOutputOption = copiedTokens.stream()
                    .anyMatch(token -> "--output".equals(token) || token.startsWith("--output="));
            String cdTarget = "cd".equals(commandName) ? firstNonOptionArgument : null;

            return new Segment(
                    copiedTokens,
                    commandName,
                    gitSubcommand,
                    pathLikeArgs,
                    redirections,
                    hasRedirection,
                    hasUnresolvedExpansion,
                    inPlaceMutation,
                    findMutation,
                    hasGitOutputOption,
                    firstNonOptionArgument,
                    cdTarget,
                    gitChangeDirectoryTarget);
        }

        public boolean isReadOnlyGit() {
            return gitSubcommand != null && READ_ONLY_GIT.contains(gitSubcommand);
        }

        public boolean isMutatingCommand() {
            if (commandName == null) {
                return false;
            }
            if (MUTATING_COMMANDS.contains(commandName)) {
                return true;
            }
            if (inPlaceMutation || findMutation) {
                return true;
            }
            return "git".equals(commandName) && gitSubcommand != null && MUTATING_GIT.contains(gitSubcommand);
        }

        public boolean isBasicReadOnlyCommand() {
            if (commandName == null || hasRedirection) {
                return false;
            }
            if (!BASIC_READ_COMMANDS.contains(commandName)) {
                return false;
            }
            if ("git".equals(commandName) && (!isReadOnlyGit() || hasGitOutputOption)) {
                return false;
            }
            return !findMutation && !inPlaceMutation;
        }

        public Path gitWorkTree(Path currentDir) {
            if (gitChangeDirectoryTarget == null || containsExpansion(gitChangeDirectoryTarget)) {
                return null;
            }
            return resolvePath(gitChangeDirectoryTarget, currentDir);
        }
    }

    public static boolean containsExpansion(String token) {
        return token != null && (token.contains("$") || token.contains("`"));
    }

    public static boolean isPathLike(String token) {
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

    public static Path resolvePath(String token, Path currentDir) {
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
            if (ch == '&') {
                flushToken(tokens, token);
                if (i + 1 < command.length() && command.charAt(i + 1) == '&') {
                    tokens.add("&&");
                    i++;
                } else {
                    tokens.add("&");
                }
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

    private static boolean isAssignment(String token) {
        int equals = token.indexOf('=');
        if (equals <= 0) {
            return false;
        }
        String name = token.substring(0, equals);
        return name.chars().allMatch(ch -> Character.isLetterOrDigit(ch) || ch == '_')
                && !Character.isDigit(name.charAt(0));
    }

    private static boolean isRedirection(String token) {
        return ">".equals(token) || ">>".equals(token) || "<".equals(token) || "<<".equals(token);
    }

    private static Redirection inlineRedirection(String token) {
        Matcher matcher = INLINE_REDIRECTION.matcher(token);
        if (!matcher.matches()) {
            return null;
        }
        return new Redirection(matcher.group(1), matcher.group(2), true);
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
            return tokens.stream().anyMatch(token -> "-i".equals(token) || token.startsWith("-i."));
        }
        if ("sort".equals(commandName)) {
            return tokens.stream().anyMatch("-o"::equals);
        }
        return false;
    }

    private static String baseName(String command) {
        int slash = command.lastIndexOf('/');
        return slash >= 0 ? command.substring(slash + 1) : command;
    }
}
