package madacode.cli.slash;

import madacode.render.BlockSpacing;
import madacode.tui.Screen;
import madacode.tui.theme.Tk;

final class SlashFeedback {

    private SlashFeedback() {
    }

    static void muted(Screen screen, String line) {
        BlockSpacing.scrollbackBlock(screen, Tk.dim(line));
    }
}
