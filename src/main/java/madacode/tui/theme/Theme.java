package madacode.tui.theme;

import org.jline.utils.AttributedStyle;

/**
 * Resolves a semantic {@link Token} to a concrete style. Themes are
 * immutable; switching themes happens by replacing the active instance
 * via {@link Themes#setActive(Theme)}.
 */
public interface Theme {

    AttributedStyle styleOf(Token token);
}
