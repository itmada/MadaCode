package madacode.tui.widget;

import java.io.IOException;
import java.util.Optional;

/**
 * Narrow interface for any prompt that lets the user pick from a list of
 * typed options.
 *
 * <p>Both the bottom-pane {@link ChoicePrompt} and the new inline
 * {@link madacode.tui.inline.InlineChoicePrompt} implement this,
 * so callers like {@code StartupSessionLauncher} can be injection-agnostic.
 */
@FunctionalInterface
public interface ChoicePrompter<T> {

    /**
     * Present the model and wait for the user to select an option.
     *
     * @param model the choice model (title, options, footer, etc.)
     * @return the selected value, or empty if the user cancelled.
     * @throws IOException on terminal I/O failure.
     */
    Optional<T> choose(ChoicePrompt.Model<T> model) throws IOException;
}
