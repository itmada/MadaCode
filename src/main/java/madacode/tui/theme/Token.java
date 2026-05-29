package madacode.tui.theme;

/**
 * Semantic style roles consumed by the TUI. A {@link Theme} maps each token
 * to a concrete {@link org.jline.utils.AttributedStyle}; rendering code never
 * references colors directly.
 */
public enum Token {
    // Generic text
    MUTED,
    EMPHASIS,

    // Status bullets / row state
    SUCCESS,
    FAILURE,
    RUNNING,
    INFO,
    THINKING_PULSE,

    // Tagged prefixes like [warn] / [error]
    TAG_INFO,
    TAG_WARN,
    TAG_ERROR,

    // Tool cards
    TOOL_NAME,
    TOOL_ARG,
    FILE_PATH,

    // Diff rendering
    DIFF_ADD,
    DIFF_DEL,
    DIFF_HUNK,

    // Markdown
    HEADING,
    INLINE_CODE,
    CODE_FENCE,
    QUOTE,
    LINK,

    // Status bar
    STATUS_KEY,
    STATUS_VAL,
    STATUS_MODE_AUTO,
    STATUS_MODE_PLAN,
    TIP_AUTO,
    TIP_PLAN,
    MODE_INDICATOR_AUTO,
    MODE_INDICATOR_PLAN,

    // Prompt
    PROMPT_ACTIVE,
    PROMPT_HISTORY
}
