package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.ToolUseContext;
import madacode.tool.Tool;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class BashSafetyPermissionRule implements PermissionRule {

    public static final String SOURCE = "bash_safety";

    private static final Pattern RM_RF_ROOT = Pattern.compile("(?i)(^|[;&|]\\s*)rm\\s+-[^;&|]*r[^;&|]*f[^;&|]*\\s+(/|/\\*|~)(\\s|$)");
    private static final Pattern RM_RF_WILDCARD = Pattern.compile("(?i)(^|[;&|]\\s*)rm\\s+-[^;&|]*r[^;&|]*f[^;&|]*\\s+(\\*|\\.\\s*)($|\\s)");
    private static final Pattern SUDO = Pattern.compile("(?i)(^|[;&|]\\s*)sudo(\\s|$)");
    private static final Pattern CHMOD_RECURSIVE_777 = Pattern.compile("(?i)(^|[;&|]\\s*)chmod\\s+-R\\s+777(\\s|$)");
    private static final Pattern CHOWN_RECURSIVE = Pattern.compile("(?i)(^|[;&|]\\s*)chown\\s+-R(\\s|$)");
    private static final Pattern WRITE_SENSITIVE_PATH = Pattern.compile(
            "(?i)(>|>>|tee\\s+)\\s*"
            + "("
            + "/etc/"
            + "|~/.ssh/"
            + "|\\$HOME/.ssh/"
            + "|~/\\.bashrc"
            + "|~/\\.zshrc"
            + "|~/\\.profile"
            + "|~/\\.bash_profile"
            + "|~/\\.zprofile"
            + "|~/\\.gitconfig"
            + "|~/\\.gitmodules"
            + "|~/\\.mcp\\.json"
            + "|~/\\.claude\\.json"
            + "|~/\\.ripgreprc"
            + "|\\$HOME/\\.bashrc"
            + "|\\$HOME/\\.zshrc"
            + "|\\$HOME/\\.profile"
            + "|\\$HOME/\\.bash_profile"
            + "|\\$HOME/\\.zprofile"
            + "|\\$HOME/\\.gitconfig"
            + "|\\$HOME/\\.gitmodules"
            + "|\\$HOME/\\.mcp\\.json"
            + "|\\$HOME/\\.claude\\.json"
            + "|\\$HOME/\\.ripgreprc"
            + ")");
    private static final Pattern PIPE_TO_SHELL = Pattern.compile("(?i)(curl|wget)\\b.*\\|\\s*(bash|sh)(\\s|$)");

    @Override
    public Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        if (!"bash".equals(tool.name())) {
            return Optional.empty();
        }

        String command = input.path("command").asText("");
        Optional<String> reason = dangerousReason(command);
        return reason.map(value -> PermissionDecision.deny(
                "Dangerous bash command denied: " + value,
                SOURCE));
    }

    private Optional<String> dangerousReason(String command) {
        String normalized = command == null ? "" : command.trim();
        if (normalized.isBlank()) {
            return Optional.empty();
        }

        if (RM_RF_ROOT.matcher(normalized).find()) {
            return Optional.of("recursive force delete targets root or home");
        }
        if (RM_RF_WILDCARD.matcher(normalized).find()) {
            return Optional.of("recursive force delete targets a wildcard/current directory");
        }
        if (SUDO.matcher(normalized).find()) {
            return Optional.of("sudo requires elevated privileges");
        }
        if (CHMOD_RECURSIVE_777.matcher(normalized).find()) {
            return Optional.of("recursive chmod 777 is unsafe");
        }
        if (CHOWN_RECURSIVE.matcher(normalized).find()) {
            return Optional.of("recursive chown is unsafe");
        }
        if (WRITE_SENSITIVE_PATH.matcher(normalized).find()) {
            return Optional.of("writes to a sensitive system or SSH path");
        }
        if (PIPE_TO_SHELL.matcher(normalized.toLowerCase(Locale.ROOT)).find()) {
            return Optional.of("downloads remote content and pipes it to a shell");
        }

        return Optional.empty();
    }
}
