package madacode.bootstrap;

import madacode.cli.InterruptController;
import madacode.cli.JLineRepl;
import madacode.permission.JLineApprovalPrompt;
import madacode.tui.JLineScreen;

import org.jline.terminal.Terminal;

final class TerminalAssembly {

    private TerminalAssembly() {
    }

    static TerminalRuntime create(BootstrapResources resources) {
        if (!isInteractiveTerminal()) {
            return new TerminalRuntime(null, null, null, null);
        }
        Terminal terminal = JLineRepl.createTerminal();
        JLineScreen screen = new JLineScreen(terminal);
        resources.closeOnBootstrapFailure(() -> {
            screen.shutdown();
            terminal.close();
        });
        InterruptController interrupts = new InterruptController(terminal);
        JLineApprovalPrompt approval = new JLineApprovalPrompt(
                screen, terminal, interrupts, interrupts::interrupt);
        return new TerminalRuntime(terminal, screen, interrupts, approval);
    }

    private static boolean isInteractiveTerminal() {
        String term = System.getenv("TERM");
        return System.console() != null
                && !"true".equalsIgnoreCase(System.getenv("CI"))
                && !"true".equalsIgnoreCase(System.getenv("MADA_NO_PICKER"))
                && (term == null || !"dumb".equalsIgnoreCase(term));
    }
}
