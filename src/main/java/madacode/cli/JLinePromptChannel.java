package madacode.cli;

import madacode.tui.Suspendable;
import madacode.tui.Screen;
import madacode.tui.inline.InlineChoicePrompt;
import madacode.tui.widget.ChoicePrompt;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Interactive prompt channel that wraps the REPL's {@link LineReader}.
 *
 * <p>Constructed with all dependencies; no deferred init required.
 * {@link #isAvailable()} always returns {@code true} — construction is the
 * proof that the channel is ready.
 */
public final class JLinePromptChannel implements UserPromptChannel {

    private final LineReader lineReader;
    private final Screen screen;
    private final Terminal terminal;
    private final Suspendable interrupts;
    private final Consumer<String> onInterrupt;

    public JLinePromptChannel(LineReader lineReader, Screen screen,
                              Terminal terminal, Suspendable interrupts) {
        this(lineReader, screen, terminal, interrupts, null);
    }

    public JLinePromptChannel(LineReader lineReader, Screen screen,
                              Terminal terminal, Suspendable interrupts,
                              Consumer<String> onInterrupt) {
        this.lineReader  = Objects.requireNonNull(lineReader, "lineReader");
        this.screen      = Objects.requireNonNull(screen, "screen");
        this.terminal    = Objects.requireNonNull(terminal, "terminal");
        this.interrupts  = Objects.requireNonNull(interrupts, "interrupts");
        this.onInterrupt = onInterrupt;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public Optional<String> chooseOne(String title, List<ChannelOption> options) {
        List<ChoicePrompt.Option<String>> choices = options.stream()
                .map(o -> new ChoicePrompt.Option<>(
                        o.label(), o.label(), o.description(), ""))
                .toList();
        ChoicePrompt.Model<String> model = new ChoicePrompt.Model<>(
                title,
                "",
                choices,
                "↑/↓ select   Enter confirm   Esc cancel",
                0);

        return withPausedInterrupts(() -> {
            try {
                return new InlineChoicePrompt<String>(
                        screen, terminal, interrupts, onInterrupt).choose(model);
            } catch (IOException e) {
                fireInterrupt("eof");
                return Optional.<String>empty();
            }
        });
    }

    @Override
    public Optional<List<String>> chooseMany(String title, List<ChannelOption> options) {
        String prompt = buildMultiSelectPrompt(title, options);
        return withPausedInterrupts(() -> {
            String line;
            try {
                line = lineReader.readLine(prompt);
            } catch (UserInterruptException e) {
                fireInterrupt("sigint");
                return Optional.<List<String>>empty();
            } catch (EndOfFileException e) {
                fireInterrupt("eof");
                return Optional.<List<String>>empty();
            }
            if (line == null) {
                fireInterrupt("eof");
                return Optional.<List<String>>empty();
            }
            if (line.isBlank()) return Optional.<List<String>>empty();
            List<String> selected = new ArrayList<>();
            for (String part : line.split("[,\\s]+")) {
                try {
                    int idx = Integer.parseInt(part.trim()) - 1;
                    if (idx >= 0 && idx < options.size()) {
                        selected.add(options.get(idx).label());
                    }
                } catch (NumberFormatException ignored) {}
            }
            return selected.isEmpty() ? Optional.empty() : Optional.of(selected);
        });
    }

    @Override
    public Optional<String> freeText(String prompt) {
        return withPausedInterrupts(() -> {
            String line;
            try {
                line = lineReader.readLine(prompt);
            } catch (UserInterruptException e) {
                fireInterrupt("sigint");
                return Optional.<String>empty();
            } catch (EndOfFileException e) {
                fireInterrupt("eof");
                return Optional.<String>empty();
            }
            if (line == null) {
                fireInterrupt("eof");
                return Optional.<String>empty();
            }
            if (line.isBlank()) return Optional.<String>empty();
            return Optional.of(line.trim());
        });
    }

    @Override
    public boolean confirm(String prompt) {
        return withPausedInterrupts(() -> {
            String line;
            try {
                line = lineReader.readLine(prompt);
            } catch (UserInterruptException e) {
                fireInterrupt("sigint");
                return false;
            } catch (EndOfFileException e) {
                fireInterrupt("eof");
                return false;
            }
            if (line == null) {
                fireInterrupt("eof");
                return false;
            }
            return "y".equalsIgnoreCase(line.trim());
        });
    }

    private <T> T withPausedInterrupts(java.util.function.Supplier<T> action) {
        interrupts.pause();
        try {
            return action.get();
        } finally {
            interrupts.resume();
        }
    }

    private static String buildMultiSelectPrompt(String title, List<ChannelOption> options) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n").append(title).append("\n\n");
        for (int i = 0; i < options.size(); i++) {
            ChannelOption opt = options.get(i);
            sb.append("  ").append(i + 1).append(". ").append(opt.label());
            if (opt.description() != null && !opt.description().isBlank()) {
                sb.append(" — ").append(opt.description());
            }
            sb.append("\n");
        }
        sb.append("\n  Select (comma-separated numbers, e.g. 1,3): ");
        return sb.toString();
    }

    private void fireInterrupt(String reason) {
        if (onInterrupt != null) onInterrupt.accept(reason);
    }
}
