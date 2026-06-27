package madacode.render;

import madacode.tui.Screen;
import madacode.tui.theme.Tk;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class ExpandableHistory {

    private final AtomicReference<StageWriter.Stage> latest = new AtomicReference<>();

    public void set(StageWriter.Stage stage) {
        if (stage != null && stage.hasMore() && !stage.verbose().isEmpty()) {
            latest.set(stage);
        }
    }

    public Optional<StageWriter.Stage> take() {
        return Optional.ofNullable(latest.getAndSet(null));
    }

    public void expandInto(Screen screen) {
        take().ifPresentOrElse(stage -> {
            List<String> lines = new ArrayList<>(StageWriter.renderVerbose(stage));
            screen.commitBlock(lines);
        }, () -> screen.commitBlock(Tk.dim("(nothing to expand)")));
    }
}
