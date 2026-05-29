package madacode.cli.slash;

import madacode.tui.Screen;
import madacode.tui.TerminalKeys;
import madacode.tui.widget.CommandPalettePanel;
import madacode.tui.JLineScreen;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Inline slash-command composer with palette-driven completion.
 *
 * <p>Analogous to {@link madacode.tui.inline.InlineChoicePrompt}: enters
 * raw mode, renders via {@code setLiveModal}, reads keystrokes, and returns
 * the completed command string on Enter (or empty on Esc/Ctrl-C).
 *
 * <p>The palette has two modes:
 * <ul>
 *   <li><b>CMD</b> — no space in input. Shows matching commands from the
 *       registry.</li>
 *   <li><b>ARG</b> — space present after a matched command. Shows argument
 *       candidates from the command's {@link ArgumentProvider}.</li>
 * </ul>
 */
public final class SlashComposer {

    private final SlashCommandRegistry registry;
    private final SlashContext slashContext;
    private final Screen screen;
    private final JLineScreen jlineScreen;
    private final Terminal terminal;

    private static final String CMD_FOOTER = "type to filter   ↑↓ select   Tab complete   Enter submit   Esc cancel";
    private static final String ARG_FOOTER = "type to filter   ↑↓ select   Enter submit   Esc cancel";

    public SlashComposer(SlashCommandRegistry registry,
                         SlashContext slashContext,
                         Screen screen,
                         JLineScreen jlineScreen,
                         Terminal terminal) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.slashContext = Objects.requireNonNull(slashContext, "slashContext");
        this.screen = Objects.requireNonNull(screen, "screen");
        this.jlineScreen = Objects.requireNonNull(jlineScreen, "jlineScreen");
        this.terminal = Objects.requireNonNull(terminal, "terminal");
    }

    // ---- state ---------------------------------------------------------

    private enum Mode { CMD, ARG }

    private static final class State {
        String input;
        int cursor;
        Mode mode;
        int selected;
        List<CommandPalettePanel.PaletteCandidate> candidates = List.of();
        String matchedCommand = "";  // command name without leading /
    }

    // ---- public API ----------------------------------------------------

    /**
     * Enter the compose loop with an initial buffer (e.g. "/").
     *
     * @param initialBuffer starting input text (must start with "/")
     * @return the completed command string, or empty if cancelled
     */
    public Optional<String> compose(String initialBuffer) throws IOException {
        Objects.requireNonNull(initialBuffer, "initialBuffer");
        if (!initialBuffer.startsWith("/")) {
            return Optional.empty();
        }

        State s = new State();
        s.input = initialBuffer;
        s.cursor = initialBuffer.length();
        s.mode = Mode.CMD;
        s.selected = 0;
        rebuildCandidates(s);

        jlineScreen.enterComposePhase();
        try {
            Attributes previous = terminal.enterRawMode();
            try {
                screen.setCursorVisible(false);
                while (true) {
                    int width = screen.width();
                    screen.setLiveModal(render(s, width));

                    TerminalKeys.KeyPress key = TerminalKeys.readKey(terminal.reader());

                    switch (key.key()) {
                        case ENTER -> {
                            String result = commitOnEnter(s);
                            screen.clearLiveModal();
                            return Optional.of(result);
                        }
                        case ESCAPE, CTRL_C, EOF -> {
                            screen.clearLiveModal();
                            return Optional.empty();
                        }
                        case BACKSPACE -> {
                            if (s.cursor > 1) {
                                s.input = s.input.substring(0, s.cursor - 1)
                                        + s.input.substring(s.cursor);
                                s.cursor--;
                                onInputChanged(s);
                            } else if (s.cursor == 1 && s.input.length() == 1) {
                                screen.clearLiveModal();
                                return Optional.empty();
                            }
                            // else: cursor at 0 or at 1 with content after — ignored
                        }
                        case LEFT -> {
                            if (s.cursor > 0) s.cursor--;
                        }
                        case RIGHT -> {
                            if (s.cursor < s.input.length()) s.cursor++;
                        }
                        case UP -> {
                            if (!s.candidates.isEmpty()) {
                                s.selected = Math.floorMod(s.selected - 1, s.candidates.size());
                            }
                        }
                        case DOWN -> {
                            if (!s.candidates.isEmpty()) {
                                s.selected = Math.floorMod(s.selected + 1, s.candidates.size());
                            }
                        }
                        case TAB -> {
                            s = applyTab(s);
                        }
                        case DELETE -> {
                            if (s.cursor < s.input.length()) {
                                s.input = s.input.substring(0, s.cursor)
                                        + s.input.substring(s.cursor + 1);
                                onInputChanged(s);
                            }
                        }
                        default -> {
                            if (key.isPrintable()) {
                                char ch = (char) key.ch();
                                s.input = s.input.substring(0, s.cursor)
                                        + ch
                                        + s.input.substring(s.cursor);
                                s.cursor++;
                                onInputChanged(s);
                            }
                        }
                    }
                }
            } finally {
                screen.setCursorVisible(true);
                terminal.setAttributes(previous);
            }
        } finally {
            jlineScreen.exitComposePhase();
        }
    }

    // ---- input change --------------------------------------------------

    private void onInputChanged(State s) {
        // Detect mode transitions
        int spaceIdx = s.input.indexOf(' ', 1); // skip leading /
        if (spaceIdx < 0) {
            s.mode = Mode.CMD;
            s.matchedCommand = "";
        } else {
            String cmdPart = s.input.substring(1, spaceIdx).strip().toLowerCase(Locale.ROOT);
            Optional<SlashCommand> found = registry.find(cmdPart);
            if (found.isPresent()) {
                s.mode = Mode.ARG;
                s.matchedCommand = found.get().name();
            } else {
                s.mode = Mode.CMD;
                s.matchedCommand = "";
            }
        }
        rebuildCandidates(s);
    }

    private void rebuildCandidates(State s) {
        if (s.mode == Mode.ARG && !s.matchedCommand.isBlank()) {
            int spaceIdx = s.input.indexOf(' ', 1);
            String partial = spaceIdx >= 0 ? s.input.substring(spaceIdx + 1).stripLeading() : "";
            Optional<SlashCommand> cmd = registry.find(s.matchedCommand);
            if (cmd.isPresent()) {
                Optional<ArgumentProvider> provider = cmd.get().argumentProvider(slashContext);
                if (provider.isPresent()) {
                    List<ArgumentProvider.Candidate> args = provider.get().candidates(partial);
                    s.candidates = args.stream()
                            .map(c -> new CommandPalettePanel.PaletteCandidate(c.value(), c.description()))
                            .toList();
                } else {
                    s.candidates = List.of();
                }
            } else {
                s.candidates = List.of();
            }
        } else {
            // CMD mode: filter registry palette entries by input prefix.
            String prefix = s.input.startsWith("/") ? s.input.substring(1).toLowerCase(Locale.ROOT) : "";
            s.candidates = registry.paletteEntries().stream()
                    .filter(e -> prefix.isBlank()
                            || e.command().substring(1).toLowerCase(Locale.ROOT).startsWith(prefix))
                    .map(e -> new CommandPalettePanel.PaletteCandidate(e.command(), e.description()))
                    .toList();
        }
        s.selected = s.candidates.isEmpty() ? -1
                : Math.clamp(s.selected, 0, s.candidates.size() - 1);
    }

    // ---- commit --------------------------------------------------------

    private String commitOnEnter(State s) {
        if (s.mode == Mode.CMD) {
            // Check if the current input already parses as a valid command
            String cmdPart = s.input.startsWith("/") ? s.input.substring(1).strip() : "";
            if (registry.find(cmdPart).isPresent()) {
                return s.input.strip();
            }
            // Auto-complete from selection
            if (s.selected >= 0 && s.selected < s.candidates.size()) {
                String selectedCmd = s.candidates.get(s.selected).primary();
                return selectedCmd.strip();
            }
            return s.input.strip();
        } else {
            // ARG mode: auto-complete selected arg if available
            int spaceIdx = s.input.indexOf(' ', 1);
            if (spaceIdx < 0) {
                // Defensive: should not happen in ARG mode, but guard against
                // state inconsistency.
                return s.input.strip();
            }
            String base = s.input.substring(0, spaceIdx + 1);
            if (s.selected >= 0 && s.selected < s.candidates.size()) {
                return (base + s.candidates.get(s.selected).primary()).strip();
            }
            return s.input.strip();
        }
    }

    private State applyTab(State s) {
        if (s.candidates.isEmpty()) return s;
        int idx = Math.max(0, s.selected);
        if (idx >= s.candidates.size()) return s;

        String pick = s.candidates.get(idx).primary(); // e.g. "/model" or "claude-opus-4-7"

        if (s.mode == Mode.CMD) {
            // Complete the command name
            s.input = pick;
            s.cursor = s.input.length();
            // Check if command takes arguments
            String cmdName = pick.startsWith("/") ? pick.substring(1) : pick;
            Optional<SlashCommand> cmd = registry.find(cmdName);
            if (cmd.isPresent() && cmd.get().argumentProvider(slashContext).isPresent()) {
                s.input = pick + " ";
                s.cursor = s.input.length();
                s.mode = Mode.ARG;
                s.matchedCommand = cmd.get().name();
            }
        } else {
            // ARG mode: complete the argument
            int spaceIdx = s.input.indexOf(' ', 1);
            String base = spaceIdx >= 0 ? s.input.substring(0, spaceIdx + 1) : "";
            s.input = base + pick;
            s.cursor = s.input.length();
        }
        rebuildCandidates(s);
        return s;
    }

    // ---- render --------------------------------------------------------

    private List<String> render(State s, int width) {
        String title;
        if (s.mode == Mode.ARG && !s.matchedCommand.isBlank()) {
            title = "/" + s.matchedCommand;
        } else {
            title = "Commands";
        }
        String footer = s.mode == Mode.ARG ? ARG_FOOTER : CMD_FOOTER;

        CommandPalettePanel.View view = new CommandPalettePanel.View(
                title, s.input, s.cursor,
                s.candidates, s.selected, footer);

        List<org.jline.utils.AttributedString> lines = CommandPalettePanel.render(view, width);
        List<String> result = new ArrayList<>(lines.size());
        for (org.jline.utils.AttributedString line : lines) {
            result.add(line.toAnsi());
        }
        return result;
    }
}
