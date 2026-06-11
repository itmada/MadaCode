package madacode.render;

import madacode.tui.theme.Tk;
import madacode.tui.theme.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class StageWriter {

    private StageWriter() {}

    private static final int TITLE_LABEL_WIDTH = 8;

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

    /**
     * Status glyph: shape varies with state so success/failure remain
     * distinguishable without color (accessibility). All glyphs are
     * single-column under wcwidth.
     */
    public static String glyph(Status status) {
        return switch (status) {
            case RUNNING, SUCCESS -> "●";
            case FAILED -> "✗";
            case DENIED -> "⊘";
            case INFO -> "○";
            case WARN -> "▲";
        };
    }

    public static List<String> render(Stage stage) {
        Objects.requireNonNull(stage, "stage");
        List<String> lines = new ArrayList<>();
        lines.add(colored(stage.status(), glyph(stage.status())) + " " + styledTitle(stage.title()));
        for (int i = 0; i < stage.summary().size(); i++) {
            boolean last = i == stage.summary().size() - 1
                    && !(stage.hasMore() && !stage.verbose().isEmpty());
            String prefix = "  " + colored(stage.status(), last ? "╰─" : "├─") + " ";
            lines.add(prefix + colorSummary(stage.status(), stage.summary().get(i)));
        }
        int hidden = stage.verbose().size();
        if (stage.hasMore() && hidden > 0) {
            lines.add("  " + colored(stage.status(), "╰─") + " "
                    + Tk.dim("ctrl+o to expand · " + hidden + " lines hidden"));
        }
        return lines;
    }

    public static List<String> renderVerbose(Stage stage) {
        if (stage == null || stage.verbose().isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < stage.verbose().size(); i++) {
            String prefix = "  " + colored(stage.status(),
                    i == stage.verbose().size() - 1 ? "╰─" : "├─") + " ";
            lines.add(prefix + Tk.dim(stage.verbose().get(i)));
        }
        return lines;
    }

    private static String styledTitle(String title) {
        TitleParts parts = splitTitle(title);
        if (parts.detail().isBlank()) {
            return Tk.toolName(parts.label());
        }
        return Tk.toolName(padEnd(parts.label(), TITLE_LABEL_WIDTH)) + parts.detail();
    }

    private static TitleParts splitTitle(String title) {
        String clean = Objects.requireNonNullElse(title, "").strip();
        int open = clean.indexOf('(');
        if (open > 0 && clean.endsWith(")")) {
            String label = clean.substring(0, open).strip();
            String detail = clean.substring(open + 1, clean.length() - 1).strip();
            if (!label.isBlank() && !detail.isBlank()) {
                return new TitleParts(label, detail);
            }
        }
        return new TitleParts(clean, "");
    }

    private static String padEnd(String value, int width) {
        int displayWidth = Tk.displayWidth(value);
        if (displayWidth >= width) {
            return value + " ";
        }
        return value + " ".repeat(width - displayWidth);
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

    private record TitleParts(String label, String detail) {}
}
