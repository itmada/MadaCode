package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.permission.bash.BashCommandModel;
import madacode.tool.Tool;
import madacode.tool.ToolNames;

import java.util.Optional;
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
        BashCommandModel model = BashCommandModel.parse(normalized);
        for (BashCommandModel.Segment segment : model.segments()) {
            Optional<String> reason = mutatingSegmentReason(segment);
            if (reason.isPresent()) {
                return reason;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> mutatingSegmentReason(BashCommandModel.Segment segment) {
        if (segment.commandName() == null) {
            return Optional.empty();
        }
        String command = segment.commandName();
        if (segment.isMutatingCommand()) {
            if ("git".equals(command) && segment.gitSubcommand() != null) {
                return Optional.of("git " + segment.gitSubcommand() + " mutates repository state");
            }
            if (segment.inPlaceMutation()) {
                if ("sort".equals(command)) {
                    return Optional.of("sort -o mutates files");
                }
                return Optional.of(command + " in-place editing mutates files");
            }
            if (segment.findMutation()) {
                return Optional.of("find action can mutate files");
            }
            return Optional.of(command + " mutates files or permissions");
        }
        return Optional.empty();
    }
}
