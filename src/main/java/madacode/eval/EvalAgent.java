package madacode.eval;

import java.util.Locale;

/**
 * Which agent drives an eval run. {@code MADACODE} runs the case through MadaCode's own
 * model loop; {@code CLAUDE} runs the same case through the external Claude Code CLI.
 *
 * <p>Process dimensions (trajectory / safety / dialog) are MadaCode-specific: the external
 * Claude Code process does not flow through MadaCode's tool pipeline, so those dimensions
 * have no evidence. Cases that gate on them are skipped under {@code CLAUDE}.
 */
public enum EvalAgent {
    MADACODE,
    CLAUDE;

    public static EvalAgent parse(String value) {
        if (value == null || value.isBlank()) {
            return MADACODE;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "madacode" -> MADACODE;
            case "claude", "claude-code" -> CLAUDE;
            default -> throw new IllegalArgumentException(
                    "unknown agent '" + value + "'; expected madacode or claude");
        };
    }
}
