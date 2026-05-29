package madacode.render;

import madacode.tui.theme.Tk;
import madacode.tui.theme.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class StageWriter {

    private StageWriter() {}

    public enum Status { RUNNING, SUCCESS, FAILED, DENIED, INFO, WARN }

    public record Stage(
            Status status,
            String title,
            List<String> summary,
            List<String> verbose,
            boolean hasMore) {
        public Stage {
            status = Objects.requireNonNull(status, "status");
            title = Objects.requireNonNullElse(title, "");
            summary = List.copyOf(Objects.requireNonNullElse(summary, List.of()));
            verbose = List.copyOf(Objects.requireNonNullElse(verbose, List.of()));
        }
    }

    public static List<String> render(Stage stage) {
        Objects.requireNonNull(stage, "stage");
        List<String> lines = new ArrayList<>();
        lines.add(colored(stage.status(), "✣") + " " + Tk.toolName(stage.title()));
        for (int i = 0; i < stage.summary().size(); i++) {
            String prefix = i == 0 ? "  " + colored(stage.status(), "⎿") + " " : "     ";
            lines.add(prefix + colorSummary(stage.status(), stage.summary().get(i)));
        }
        int hidden = stage.verbose().size();
        if (stage.hasMore() && hidden > 0) {
            lines.add("     " + Tk.dim("(ctrl+o to expand · " + hidden + " lines hidden)"));
        }
        return lines;
    }

    public static List<String> renderVerbose(Stage stage) {
        if (stage == null || stage.verbose().isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < stage.verbose().size(); i++) {
            String prefix = i == 0 ? "  " + colored(stage.status(), "⎿") + " " : "     ";
            lines.add(prefix + Tk.dim(stage.verbose().get(i)));
        }
        return lines;
    }

    private static String colorSummary(Status status, String text) {
        return switch (status) {
            case FAILED, DENIED -> Tk.failure(text);
            case RUNNING -> Tk.running(text);
            case WARN -> Tk.apply(Token.TAG_WARN, text);
            case INFO -> Tk.info(text);
            case SUCCESS -> text;
        };
    }

    private static String colored(Status status, String text) {
        return switch (status) {
            case RUNNING -> Tk.running(text);
            case SUCCESS -> Tk.success(text);
            case FAILED, DENIED -> Tk.failure(text);
            case INFO -> Tk.info(text);
            case WARN -> Tk.apply(Token.TAG_WARN, text);
        };
    }
}
