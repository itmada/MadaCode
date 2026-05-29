package madacode.tui.inline;

import madacode.tui.JLineScreen;
import madacode.tui.widget.ChoicePrompt;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Quick demo: launches an inline choice prompt with three hard-coded options.
 * Compile and run to verify Phase 0 infrastructure works end-to-end.
 *
 * <pre>{@code
 * ./mvnw -q -DskipTests package
 * java -cp target/MadaCode-0.1.0-SNAPSHOT.jar \
 *   madacode.tui.inline.InlineChoicePromptDemoMain
 * }</pre>
 */
public final class InlineChoicePromptDemoMain {

    public static void main(String[] args) throws IOException {
        Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .jna(false)
                .build();

        JLineScreen screen = new JLineScreen(terminal);
        InlineChoicePrompt<String> prompt = new InlineChoicePrompt<>(screen, terminal, null);

        List<ChoicePrompt.Option<String>> options = List.of(
                new ChoicePrompt.Option<>("red", "Red", "The color of fire", ""),
                new ChoicePrompt.Option<>("blue", "Blue", "The color of sky", ""),
                new ChoicePrompt.Option<>("green", "Green", "The color of grass", ""));

        ChoicePrompt.Model<String> model = new ChoicePrompt.Model<>(
                "Pick a color",
                "Choose your favorite",
                options,
                "↑/↓ select  Enter confirm  Esc cancel",
                0);

        Attributes prev = terminal.enterRawMode();
        try {
            Optional<String> choice = prompt.choose(model);
            System.out.println();
            if (choice.isPresent()) {
                System.out.println("You chose: " + choice.get());
            } else {
                System.out.println("Cancelled.");
            }
        } finally {
            terminal.setAttributes(prev);
        }

        terminal.close();
    }
}
