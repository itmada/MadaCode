package madacode.tui.inline;

import madacode.tui.Screen;
import madacode.tui.Suspendable;
import madacode.tui.TerminalKeys;
import madacode.tui.widget.ChoicePanel;
import madacode.tui.widget.ChoicePrompt;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Inline choice prompt using {@link Screen#setLiveModal} for flicker-free rendering.
 *
 * <p>The picker is rendered as live region content — no manual cursor arithmetic,
 * no scrollback residue on cancel. On selection, the picker is cleared from live
 * and (optionally) acknowledged in scrollback.
 */
/**
 * Inline choice prompt using {@link Screen#setLiveModal} for flicker-free rendering.
 *
 * <p>The picker is rendered as live region content — no manual cursor arithmetic,
 * no scrollback residue on cancel. On selection, the picker is cleared from live
 * and (optionally) acknowledged in scrollback.
 *
 * <p>Raw mode is entered locally for the duration of {@link #choose}. Nesting inside
 * an active turn (where {@code InterruptController} already holds raw mode) is safe —
 * the inner snapshot is already raw, so the restore on exit is a no-op. The turn-level
 * cooked-mode restore is owned by {@code InterruptController.endTurn()}.
 */
public final class InlineChoicePrompt<T> implements madacode.tui.widget.ChoicePrompter<T> {

    private final Screen screen;
    private final Terminal terminal;
    private final Suspendable readerLock;
    private final Consumer<String> onInterrupt;

    /**
     * @param screen      the screen (live region for picker rendering)
     * @param terminal    the terminal (for reading keystrokes; must be in raw mode)
     * @param readerLock  optional suspendable to coordinate with background readers (nullable)
     */
    public InlineChoicePrompt(Screen screen, Terminal terminal, Suspendable readerLock) {
        this(screen, terminal, readerLock, null);
    }

    public InlineChoicePrompt(Screen screen, Terminal terminal, Suspendable readerLock,
                              Consumer<String> onInterrupt) {
        this.screen = Objects.requireNonNull(screen, "screen");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.readerLock = readerLock;
        this.onInterrupt = onInterrupt;
    }


    public Optional<T> choose(ChoicePrompt.Model<T> model) throws IOException {
        Objects.requireNonNull(model, "model");
        if (model.options().isEmpty()) {
            return Optional.empty();
        }
        if (model.horizontal()) {
            return chooseLegacy(model);
        }

        int selected = Math.max(0, Math.min(model.initialIndex(), model.options().size() - 1));
        StringBuilder filter = new StringBuilder();
        List<Integer> visible = allIndexes(model);

        Attributes previous = terminal.enterRawMode();
        screen.setCursorVisible(false);
        try {
            while (true) {
                int width = screen.width();
                screen.setLiveModal(renderPicker(model, visible, filter.toString(), selected, width));

                TerminalKeys.KeyPress key = TerminalKeys.readKey(terminal.reader());

                switch (key.key()) {
                    case ENTER -> {
                        if (visible.isEmpty()) {
                            continue;
                        }
                        screen.clearLiveModal();
                        return Optional.of(model.options().get(visible.get(selected)).value());
                    }
                    case ESCAPE -> {
                        if (filter.length() > 0) {
                            filter.setLength(0);
                            visible = allIndexes(model);
                            selected = 0;
                            continue;
                        }
                        screen.clearLiveModal();
                        fireInterrupt("esc");
                        return Optional.empty();
                    }
                    case CTRL_C -> {
                        screen.clearLiveModal();
                        fireInterrupt("sigint");
                        return Optional.empty();
                    }
                    case EOF -> {
                        screen.clearLiveModal();
                        fireInterrupt("eof");
                        return Optional.empty();
                    }
                    case BACKSPACE -> {
                        if (filter.length() > 0) {
                            filter.deleteCharAt(filter.length() - 1);
                            visible = filteredIndexes(model, filter.toString());
                            selected = 0;
                        }
                    }
                    case UP, LEFT -> {
                        int size = visible.size();
                        if (size > 0) {
                            selected = Math.floorMod(selected - 1, size);
                        }
                    }
                    case DOWN, RIGHT -> {
                        int size = visible.size();
                        if (size > 0) {
                            selected = Math.floorMod(selected + 1, size);
                        }
                    }
                    case PAGE_UP -> {
                        int size = visible.size();
                        if (size > 0) {
                            int step = Math.max(1, size / 2);
                            selected = clamp(selected - step, size);
                        }
                    }
                    case PAGE_DOWN -> {
                        int size = visible.size();
                        if (size > 0) {
                            int step = Math.max(1, size / 2);
                            selected = clamp(selected + step, size);
                        }
                    }
                    default -> {
                        if (key.isPrintable()) {
                            if (filter.isEmpty()) {
                                OptionalInt newSel = resolvePrintable(key.ch(), model, visible, selected);
                                if (newSel.isPresent()) {
                                    selected = newSel.getAsInt();
                                    continue;
                                }
                                if (key.ch() >= '1' && key.ch() <= '9') {
                                    continue;
                                }
                            }
                            filter.append((char) key.ch());
                            visible = filteredIndexes(model, filter.toString());
                            selected = 0;
                        }
                    }
                }
            }
        } finally {
            screen.setCursorVisible(true);
            terminal.setAttributes(previous);
        }
    }

    private Optional<T> chooseLegacy(ChoicePrompt.Model<T> model) throws IOException {
        int selected = Math.max(0, Math.min(model.initialIndex(), model.options().size() - 1));

        Attributes previous = terminal.enterRawMode();
        screen.setCursorVisible(false);
        try {
            while (true) {
                int width = screen.width();
                screen.setLiveModal(renderPicker(model, selected, width));

                TerminalKeys.KeyPress key = TerminalKeys.readKey(terminal.reader());

                switch (key.key()) {
                    case ENTER -> {
                        screen.clearLiveModal();
                        return Optional.of(model.options().get(selected).value());
                    }
                    case ESCAPE -> {
                        screen.clearLiveModal();
                        fireInterrupt("esc");
                        return Optional.empty();
                    }
                    case CTRL_C -> {
                        screen.clearLiveModal();
                        fireInterrupt("sigint");
                        return Optional.empty();
                    }
                    case EOF -> {
                        screen.clearLiveModal();
                        fireInterrupt("eof");
                        return Optional.empty();
                    }
                    case UP, LEFT -> {
                        int size = model.options().size();
                        selected = Math.floorMod(selected - 1, size);
                    }
                    case DOWN, RIGHT -> {
                        int size = model.options().size();
                        selected = Math.floorMod(selected + 1, size);
                    }
                    case PAGE_UP -> {
                        int step = Math.max(1, model.options().size() / 2);
                        selected = clamp(selected - step, model.options().size());
                    }
                    case PAGE_DOWN -> {
                        int step = Math.max(1, model.options().size() / 2);
                        selected = clamp(selected + step, model.options().size());
                    }
                    default -> {
                        if (key.isPrintable()) {
                            int newSel = resolvePrintableLegacy(key.ch(), model, selected);
                            if (newSel != selected) {
                                selected = newSel;
                            }
                        }
                    }
                }
            }
        } finally {
            screen.setCursorVisible(true);
            terminal.setAttributes(previous);
        }
    }

    // ---- rendering --------------------------------------------------

    private List<String> renderPicker(ChoicePrompt.Model<T> model, int selected, int width) {
        List<org.jline.utils.AttributedString> lines = ChoicePanel.render(
                buildView(model, selected), width);
        List<String> result = new ArrayList<>(lines.size());
        for (org.jline.utils.AttributedString line : lines) {
            result.add(line.toAnsi());
        }
        return result;
    }

    private List<String> renderPicker(
            ChoicePrompt.Model<T> model,
            List<Integer> visible,
            String filter,
            int selected,
            int width) {
        List<org.jline.utils.AttributedString> lines = ChoicePanel.render(
                buildView(model, visible, filter, selected), width);
        List<String> result = new ArrayList<>(lines.size());
        for (org.jline.utils.AttributedString line : lines) {
            result.add(line.toAnsi());
        }
        return result;
    }

    private static <T> ChoicePanel.ChoiceView buildView(
            ChoicePrompt.Model<T> model, int selected) {
        List<ChoicePanel.ChoiceOption> options = new ArrayList<>();
        for (ChoicePrompt.Option<T> opt : model.options()) {
            options.add(new ChoicePanel.ChoiceOption(
                    opt.primary(), opt.secondary(), opt.meta(), opt.hotkey()));
        }
        return new ChoicePanel.ChoiceView(
                model.title(), model.subtitle(), options, selected, model.footer(), model.horizontal());
    }

    private static <T> ChoicePanel.ChoiceView buildView(
            ChoicePrompt.Model<T> model,
            List<Integer> visible,
            String filter,
            int selected) {
        List<ChoicePanel.ChoiceOption> options = new ArrayList<>();
        for (Integer index : visible) {
            ChoicePrompt.Option<T> opt = model.options().get(index);
            options.add(new ChoicePanel.ChoiceOption(
                    opt.primary(), opt.secondary(), opt.meta(), opt.hotkey()));
        }
        return new ChoicePanel.ChoiceView(
                model.title(),
                Objects.requireNonNullElse(model.subtitle(), ""),
                options,
                selected,
                model.footer().isBlank()
                        ? "type to filter · backspace delete · esc clear/cancel"
                        : model.footer(),
                false,
                filter,
                visible.isEmpty());
    }

    // ---- key handling -----------------------------------------------

    private OptionalInt resolvePrintable(
            int ch,
            ChoicePrompt.Model<T> model,
            List<Integer> visible,
            int selected) {
        if (ch >= '1' && ch <= '9') {
            int index = ch - '1';
            if (index < visible.size()) {
                return OptionalInt.of(index);
            }
            return OptionalInt.empty();
        }
        if (!Character.isLetterOrDigit(ch)) {
            return OptionalInt.empty();
        }
        String needle = Character.toString((char) ch);
        int size = visible.size();
        for (int i = 0; i < size; i++) {
            int visibleIndex = (selected + i) % size;
            ChoicePrompt.Option<T> option = model.options().get(visible.get(visibleIndex));
            if (matchesHotkey(option, needle)) {
                return OptionalInt.of(visibleIndex);
            }
        }
        return OptionalInt.empty();
    }

    private int resolvePrintableLegacy(int ch, ChoicePrompt.Model<T> model, int selected) {
        if (ch >= '1' && ch <= '9') {
            int index = ch - '1';
            if (index < model.options().size()) {
                return index;
            }
            return selected;
        }
        if (!Character.isLetterOrDigit(ch)) {
            return selected;
        }
        String needle = Character.toString((char) ch);
        int size = model.options().size();
        for (int i = 1; i <= size; i++) {
            int index = (selected + i) % size;
            ChoicePrompt.Option<T> option = model.options().get(index);
            if (matchesHotkeyOrPrimary(option, needle)) {
                return index;
            }
        }
        return selected;
    }

    private static boolean matchesHotkey(ChoicePrompt.Option<?> option, String needle) {
        return !option.hotkey().isBlank()
                && option.hotkey().equalsIgnoreCase(needle);
    }

    private static boolean matchesHotkeyOrPrimary(
            ChoicePrompt.Option<?> option, String needle) {
        if (!option.hotkey().isBlank()
                && option.hotkey().equalsIgnoreCase(needle)) {
            return true;
        }
        return !option.primary().isBlank()
                && option.primary().regionMatches(true, 0, needle, 0, needle.length());
    }

    private static int clamp(int index, int size) {
        if (size <= 0) return 0;
        return Math.max(0, Math.min(index, size - 1));
    }

    private static <T> List<Integer> allIndexes(ChoicePrompt.Model<T> model) {
        List<Integer> all = new ArrayList<>();
        for (int i = 0; i < model.options().size(); i++) {
            all.add(i);
        }
        return all;
    }

    static <T> List<Integer> filteredIndexes(ChoicePrompt.Model<T> model, String needle) {
        if (needle.isBlank()) return allIndexes(model);
        String n = needle.toLowerCase(Locale.ROOT);
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < model.options().size(); i++) {
            ChoicePrompt.Option<T> option = model.options().get(i);
            if (option.primary().toLowerCase(Locale.ROOT).contains(n)
                    || option.secondary().toLowerCase(Locale.ROOT).contains(n)) {
                out.add(i);
            }
        }
        return out;
    }

    private void fireInterrupt(String reason) {
        if (onInterrupt != null) onInterrupt.accept(reason);
    }
}
