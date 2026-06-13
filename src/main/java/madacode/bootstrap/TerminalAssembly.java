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
}
