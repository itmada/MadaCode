package madacode.tui.inline;

import madacode.tui.Screen;
import madacode.tui.Suspendable;
import madacode.tui.TerminalKeys;
import madacode.tui.widget.QuestionCardPanel;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Inline prompt for {@code ask_user_question}: an option list (single- or
 * multi-select) plus a persistent free-text row, rendered through
 * {@link Screen#setLiveModal} (flicker-free, no scrollback residue on cancel).
 *
 * <p>The answer combines the chosen option labels with the free text, so users
 * can pick a preset <em>and</em> add a note. Focus moves across the option rows
 * and the text row; {@code Space} toggles the focused option (exclusive in
 * single-select, independent in multi-select); typing a letter jumps to the
 * text row. {@code Enter} submits, {@code Esc} cancels.
 */
public final class InlineQuestionPrompt {

    public record Option(String label, String description) {
        public Option {
            label = Objects.requireNonNullElse(label, "");
            description = Objects.requireNonNullElse(description, "");
        }
    }

    public record Spec(String header, String question, String progress,
                       List<Option> options, boolean multiSelect) {
        public Spec {
            options = List.copyOf(Objects.requireNonNullElse(options, List.of()));
        }
    }

    private final Screen screen;
    private final Terminal terminal;
    private final Suspendable readerLock;
    private final Consumer<String> onInterrupt;

    public InlineQuestionPrompt(Screen screen, Terminal terminal, Suspendable readerLock,
                                Consumer<String> onInterrupt) {
        this.screen = Objects.requireNonNull(screen, "screen");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.readerLock = readerLock;
        this.onInterrupt = onInterrupt;
    }

    /**
     * Returns the combined answer (selected labels in display order, then the
     * free text if any). {@code Optional.empty()} means the user cancelled; a
     * present-but-empty list means submitted with no selection and no text.
     */
    public Optional<List<String>> ask(Spec spec) throws IOException {
        Objects.requireNonNull(spec, "spec");
        int n = spec.options().size();
        int textRow = n;                 // focus index for the free-text row
        boolean multi = spec.multiSelect();
        boolean[] checked = new boolean[n];
        int chosen = -1;                 // single-select radio (-1 = none)
        LineEditor text = new LineEditor();
        int focus = n > 0 ? 0 : textRow;

        Attributes previous = terminal.enterRawMode();
        screen.setCursorVisible(false);
        try {
            while (true) {
                screen.setLiveModal(render(spec, checked, chosen, text, focus));
                TerminalKeys.KeyPress key = TerminalKeys.readKey(terminal.reader());

                switch (key.key()) {
                    case ENTER -> {
                        if (!multi && focus < n) {
                            chosen = focus; // Enter on an option in single-select picks it
                        }
                        screen.clearLiveModal();
                        return Optional.of(assemble(spec.options(), multi, checked, chosen, text.text()));
                    }
                    case ESCAPE -> { return cancel("esc"); }
                    case CTRL_C -> { return cancel("sigint"); }
                    case CTRL_D, EOF -> { return cancel("eof"); }
                    case UP -> focus = Math.floorMod(focus - 1, textRow + 1);
                    case DOWN -> focus = Math.floorMod(focus + 1, textRow + 1);
                    case LEFT -> {
                        if (focus == textRow) text.moveLeft();
                        else focus = Math.floorMod(focus - 1, textRow + 1);
                    }
                    case RIGHT -> {
                        if (focus == textRow) text.moveRight();
                        else focus = Math.floorMod(focus + 1, textRow + 1);
                    }
                    case HOME -> { if (focus == textRow) text.home(); }
                    case END -> { if (focus == textRow) text.end(); }
                    case BACKSPACE -> { if (focus == textRow) text.backspace(); }
                    case DELETE -> { if (focus == textRow) text.delete(); }
                    case PASTE -> { if (focus == textRow) text.insert(InlineTextPrompt.sanitize(key.text())); }
                    default -> {
                        if (key.isPrintable()) {
                            char ch = (char) key.ch();
                            if (focus == textRow) {
                                text.insert(Character.toString(ch));
                            } else if (ch == ' ') {
                                chosen = applyToggle(multi, checked, focus, chosen);
                            } else if (ch >= '1' && ch <= '9' && (ch - '1') < n) {
                                focus = ch - '1';
                                chosen = applyToggle(multi, checked, focus, chosen);
                            } else {
                                focus = textRow; // start typing → jump to the note field
                                text.insert(Character.toString(ch));
                            }
                        }
                    }
                }
            }
        } finally {
            try {
                screen.clearLiveModal();
            } finally {
                try {
                    screen.setCursorVisible(true);
                } finally {
                    terminal.setAttributes(previous);
                }
            }
        }
    }

    private Optional<List<String>> cancel(String reason) {
        screen.clearLiveModal();
        if (onInterrupt != null) onInterrupt.accept(reason);
        return Optional.empty();
    }

    // Toggle the focused option's state, returning the new single-select choice.
    private static int applyToggle(boolean multi, boolean[] checked, int index, int chosen) {
        if (index < 0 || index >= checked.length) return chosen;
        if (multi) {
            checked[index] = !checked[index];
            return chosen;
        }
        return chosen == index ? -1 : index; // radio: re-pressing clears
    }

    /**
     * Builds the answer: selected option labels (in display order) followed by
     * the trimmed free text if non-empty. Package-visible for testing.
     */
    static List<String> assemble(List<Option> options, boolean multi, boolean[] checked, int chosen, String rawText) {
        List<String> out = new ArrayList<>();
        if (multi) {
            for (int i = 0; i < options.size(); i++) {
                if (checked[i]) out.add(options.get(i).label());
            }
        } else if (chosen >= 0 && chosen < options.size()) {
            out.add(options.get(chosen).label());
        }
        String note = rawText == null ? "" : rawText.strip();
        if (!note.isEmpty()) out.add(note);
        return out;
    }

    private List<String> render(Spec spec, boolean[] checked, int chosen, LineEditor text, int focus) {
        int textRow = spec.options().size();
        List<QuestionCardPanel.OptionRow> rows = new ArrayList<>(spec.options().size());
        for (int i = 0; i < spec.options().size(); i++) {
            Option option = spec.options().get(i);
            boolean on = spec.multiSelect() ? checked[i] : chosen == i;
            boolean recommended = QuestionCardPanel.looksRecommended(option.label(), option.description());
            rows.add(new QuestionCardPanel.OptionRow(option.label(), option.description(), on, focus == i, recommended));
        }
        QuestionCardPanel.View view = new QuestionCardPanel.View(
                spec.header(), spec.question(), spec.progress(), spec.multiSelect(),
                rows, text.text(), text.cursor(), focus == textRow, footer(spec));

        List<AttributedString> lines = QuestionCardPanel.render(view, screen.width());
        List<String> ansi = new ArrayList<>(lines.size());
        for (AttributedString line : lines) ansi.add(line.toAnsi());
        return ansi;
    }

    private static String footer(Spec spec) {
        if (spec.options().isEmpty()) {
            return "type your answer · enter confirm · esc cancel";
        }
        String toggle = spec.multiSelect() ? "space toggle" : "space select";
        return "↑↓ move · " + toggle + " · type to add a note · enter confirm · esc cancel";
    }
}
