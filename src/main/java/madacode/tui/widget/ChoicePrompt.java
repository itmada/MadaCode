package madacode.tui.widget;

import java.util.List;
import java.util.Objects;

/**
 * Choice prompt model types only — the rendering and interaction now live
 * in {@link madacode.tui.inline.InlineChoicePrompt}.
 *
 * <p>Kept as a shared data definition so existing callers
 * ({@code StartupSessionLauncher}, slash-command pickers) don't need to
 * change their option / model construction.
 */
public final class ChoicePrompt {

    private ChoicePrompt() {}

    // ---- model types ---------------------------------------------------

    public record Model<T>(
            String title,
            String subtitle,
            List<Option<T>> options,
            String footer,
            int initialIndex,
            boolean horizontal) {
        public Model(String title, String subtitle, List<Option<T>> options, String footer, int initialIndex) {
            this(title, subtitle, options, footer, initialIndex, false);
        }

        public Model {
            title = Objects.requireNonNullElse(title, "");
            subtitle = Objects.requireNonNullElse(subtitle, "");
            options = List.copyOf(Objects.requireNonNullElse(options, List.of()));
            footer = Objects.requireNonNullElse(footer, "");
        }
    }

    public record Option<T>(T value, String primary, String secondary, String meta, String hotkey) {
        public Option(T value, String primary, String secondary, String meta) {
            this(value, primary, secondary, meta, "");
        }

        public Option {
            primary = Objects.requireNonNullElse(primary, "");
            secondary = Objects.requireNonNullElse(secondary, "");
            meta = Objects.requireNonNullElse(meta, "");
            hotkey = Objects.requireNonNullElse(hotkey, "");
        }
    }
}
