package madacode.cli;

import madacode.tui.Suspendable;
import madacode.tui.Screen;
import madacode.tui.inline.InlineChoicePrompt;
import madacode.tui.inline.InlineQuestionPrompt;
import madacode.tui.inline.InlineTextPrompt;
import madacode.tui.widget.ChoicePrompt;

import org.jline.terminal.Terminal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Interactive prompt channel rendered through the shared TUI screen.
 *
 * <p>Constructed with all dependencies; no deferred init required.
 * {@link #isAvailable()} always returns {@code true} — construction is the
 * proof that the channel is ready.
 */
public final class JLinePromptChannel implements UserPromptChannel {

    private final Screen screen;
    private final Terminal terminal;
    private final Suspendable interrupts;
    private final Consumer<String> onInterrupt;

    public JLinePromptChannel(Screen screen, Terminal terminal, Suspendable interrupts) {
        this(screen, terminal, interrupts, null);
    }

    public JLinePromptChannel(Screen screen, Terminal terminal, Suspendable interrupts,
                              Consumer<String> onInterrupt) {
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
    public Optional<List<String>> askQuestion(QuestionForm form) {
        List<InlineQuestionPrompt.Option> options = form.options().stream()
                .map(o -> new InlineQuestionPrompt.Option(o.label(), o.description()))
                .toList();
        InlineQuestionPrompt.Spec spec = new InlineQuestionPrompt.Spec(
                form.header(), form.question(), form.progress(), options, form.multiSelect());
        return withPausedInterrupts(() -> {
            try {
                return new InlineQuestionPrompt(screen, terminal, interrupts, onInterrupt).ask(spec);
            } catch (IOException e) {
                fireInterrupt("eof");
                return Optional.<List<String>>empty();
            }
        });
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
            Optional<String> entered;
            try {
                entered = new InlineTextPrompt(screen, terminal, interrupts, onInterrupt).read(prompt);
            } catch (IOException e) {
                fireInterrupt("eof");
                return Optional.<List<String>>empty();
            }
            if (entered.isEmpty()) {
                return Optional.<List<String>>empty();
            }
            String line = entered.get();
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
            try {
                Optional<String> entered = readInlineText(prompt);
                if (entered.isEmpty()) return Optional.<String>empty();
                String line = entered.get().trim();
                return line.isBlank() ? Optional.<String>empty() : Optional.of(line);
            } catch (IOException e) {
                fireInterrupt("eof");
                return Optional.<String>empty();
            }
        });
    }

    @Override
    public Optional<String> sensitiveText(String prompt) {
        return withPausedInterrupts(() -> {
            try {
                Optional<String> entered = readInlineText(prompt);
                return entered.filter(line -> !line.isBlank());
            } catch (IOException e) {
                fireInterrupt("eof");
                return Optional.<String>empty();
            }
        });
    }

    @Override
    public boolean confirm(String prompt) {
        List<ChannelOption> opts = List.of(
                new ChannelOption("Yes", ""),
                new ChannelOption("No", ""));
        PromptText text = PromptText.from(prompt);
        List<ChoicePrompt.Option<String>> choices = opts.stream()
                .map(o -> new ChoicePrompt.Option<>(
                        o.label(), o.label(), o.description(), ""))
                .toList();
        ChoicePrompt.Model<String> model = new ChoicePrompt.Model<>(
                text.title(),
                text.subtitle(),
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
        }).map("Yes"::equals).orElse(false);
    }

    private Optional<String> readInlineText(String prompt) throws IOException {
        return new InlineTextPrompt(screen, terminal, interrupts, onInterrupt).read(prompt);
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

    private record PromptText(String title, String subtitle) {
        static PromptText from(String prompt) {
            String text = Objects.requireNonNullElse(prompt, "").strip();
            if (text.isBlank()) {
                return new PromptText("Confirm?", "");
            }
            List<String> lines = text.lines()
                    .map(String::strip)
                    .filter(line -> !line.isBlank())
                    .toList();
            if (lines.isEmpty()) {
                return new PromptText("Confirm?", "");
            }
            String title = lines.getFirst();
            String subtitle = lines.size() == 1
                    ? ""
                    : String.join(" · ", lines.subList(1, lines.size()));
            return new PromptText(title, subtitle);
        }
    }
}
