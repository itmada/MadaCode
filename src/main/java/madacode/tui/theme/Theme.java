package madacode.tui.theme;

import org.jline.utils.AttributedStyle;

/**
 * Resolves a semantic {@link Token} to a concrete style. The runtime currently
 * uses one default implementation; this interface is kept as the future theme
 * extension point.
 */
public interface Theme {

    AttributedStyle styleOf(Token token);
}
