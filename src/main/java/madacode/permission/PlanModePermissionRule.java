package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.tool.Tool;
import madacode.tool.ToolNames;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Enforces Plan Mode's execution boundary for parameter-sensitive tools.
 */
public final class PlanModePermissionRule implements PermissionRule {

    public static final String SOURCE = "plan_mode";

    private static final Pattern SHELL_REDIRECTION =
            Pattern.compile("(^|\\s)(\\d?>|\\d?>>|>|>>|<<)(\\s|$)");
    private static final Pattern PIPE_TO_MUTATOR =
            Pattern.compile("(?i)\\|\\s*(tee|xargs\\s+(rm|mv|cp|mkdir|touch|chmod|chown|git))\\b");

    private static final Set<String> MUTATING_COMMANDS = Set.of(
            "rm", "rmdir", "mv", "cp", "mkdir", "touch", "chmod", "chown",
            "ln", "tee", "truncate", "install", "patch", "apply_patch");

    private static final Set<String> MUTATING_GIT = Set.of(
            "add", "am", "apply", "bisect", "checkout", "cherry-pick", "clean",
            "commit", "merge", "mv", "pull", "push", "rebase", "reset", "restore",
            "revert", "rm", "stash", "switch", "tag", "worktree");

    @Override
    public PermissionLayer layer() {
        return PermissionLayer.SAFETY;
    }

    @Override
    public Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        if (context == null || context.session() == null || !context.session().isPlanMode()) {
            return Optional.empty();
        }
        if (!ToolNames.BASH.equals(tool.name())) {
            return Optional.empty();
        }
        String command = input.path("command").asText("");
        Optional<String> reason = mutatingBashReason(command);
        return reason.map(value -> PermissionDecision.deny(
                "Plan Mode blocks mutating bash commands: " + value,
                layer(),
                SOURCE));
    }

    private static Optional<String> mutatingBashReason(String command) {
        String normalized = command == null ? "" : command.strip();
        if (normalized.isBlank()) {
            return Optional.empty();
        }
        if (SHELL_REDIRECTION.matcher(normalized).find()) {
            return Optional.of("shell redirection can modify files");
        }
        if (PIPE_TO_MUTATOR.matcher(normalized).find()) {
            return Optional.of("pipeline feeds a mutating command");
        }
        for (String segment : normalized.split("\\s*(?:&&|;|\\|\\||&)\\s*")) {
            Optional<String> reason = mutatingSegmentReason(segment);
            if (reason.isPresent()) {
                return reason;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> mutatingSegmentReason(String segment) {
        String trimmed = segment == null ? "" : segment.strip();
        if (trimmed.isBlank()) {
            return Optional.empty();
        }
        String[] tokens = trimmed.split("\\s+");
        String command = commandName(tokens);
        if (command == null) {
            return Optional.empty();
        }
        if (MUTATING_COMMANDS.contains(command)) {
            return Optional.of(command + " mutates files or permissions");
        }
        if (("sed".equals(command) || "perl".equals(command)) && hasInPlaceFlag(tokens)) {
            return Optional.of(command + " in-place editing mutates files");
        }
        if ("sort".equals(command) && hasExactToken(tokens, "-o")) {
            return Optional.of("sort -o mutates files");
        }
        if ("find".equals(command) && (hasExactToken(tokens, "-delete")
                || hasExactToken(tokens, "-exec")
                || hasExactToken(tokens, "-execdir"))) {
            return Optional.of("find action can mutate files");
        }
        if ("git".equals(command)) {
            String subcommand = gitSubcommand(tokens);
            if (subcommand != null && MUTATING_GIT.contains(subcommand)) {
                return Optional.of("git " + subcommand + " mutates repository state");
            }
        }
        return Optional.empty();
    }

    private static String commandName(String[] tokens) {
        if (tokens.length == 0) {
            return null;
        }
        String token = tokens[0];
        int equals = token.indexOf('=');
        int index = 0;
        while (equals > 0 && index + 1 < tokens.length) {
            token = tokens[++index];
            equals = token.indexOf('=');
        }
        int slash = token.lastIndexOf('/');
        return (slash >= 0 ? token.substring(slash + 1) : token).toLowerCase(Locale.ROOT);
    }

    private static String gitSubcommand(String[] tokens) {
        for (int i = 1; i < tokens.length; i++) {
            String token = tokens[i];
            if ("-C".equals(token)) {
                i++;
                continue;
            }
            if (token.startsWith("-")) {
                continue;
            }
            return token.toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static boolean hasInPlaceFlag(String[] tokens) {
        for (String token : tokens) {
            if ("-i".equals(token) || token.startsWith("-i.")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasExactToken(String[] tokens, String expected) {
        for (String token : tokens) {
            if (expected.equals(token)) {
                return true;
            }
        }
        return false;
    }
}
