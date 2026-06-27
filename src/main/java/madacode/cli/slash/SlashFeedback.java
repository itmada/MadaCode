package madacode.cli.slash;

import madacode.tui.Screen;
import madacode.tui.theme.Tk;

final class SlashFeedback {

    private SlashFeedback() {
    }

    static void muted(Screen screen, String line) {
        screen.commitBlock(Tk.dim(line));
    }
}
