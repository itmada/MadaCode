package madacode.render;

import madacode.tui.theme.Tk;
import madacode.tui.theme.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class StageWriter {

    private StageWriter() {}

    private static final java.util.Set<String> PATH_DETAIL_LABELS =
            java.util.Set.of("file_read", "file_write", "file_edit");

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
            case RUNNING -> "⠧";
            case SUCCESS -> "●";
            case FAILED -> "✗";
            case DENIED -> "⊘";
            case INFO -> "›";
            case WARN -> "▲";
        };
    }

    public static List<String> render(Stage stage) {
        return render(stage, null);
    }

    /**
     * Render with an optional glyph override for the RUNNING state, used by
     * live tool cards to animate a spinner frame in place of the static bullet.
     * Non-RUNNING stages ignore the override.
     */
    public static List<String> render(Stage stage, String runningGlyphOverride) {
        Objects.requireNonNull(stage, "stage");
        String glyph = (stage.status() == Status.RUNNING && runningGlyphOverride != null
                && !runningGlyphOverride.isBlank())
                ? runningGlyphOverride
                : glyph(stage.status());
        List<String> lines = new ArrayList<>();
        lines.add(colored(stage.status(), glyph) + " " + styledTitle(stage.title())
                + inlineSummary(stage.status(), stage.summary()));
        for (int i = 0; i < stage.summary().size(); i++) {
            if (i == 0) {
                continue;
            }
            boolean last = i == stage.summary().size() - 1
                    && !(stage.hasMore() && !stage.verbose().isEmpty());
            String prefix = "  " + Tk.dim(last ? "└" : "├") + " ";
            lines.add(prefix + colorSummary(stage.status(), stage.summary().get(i)));
        }
        int hidden = stage.verbose().size();
        if (stage.hasMore() && hidden > 0) {
            lines.add("  " + Tk.dim("└") + " "
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
            String prefix = "  " + Tk.dim(i == stage.verbose().size() - 1 ? "└" : "├") + " ";
            lines.add(prefix + Tk.dim(stage.verbose().get(i)));
        }
        return lines;
    }

    private static String styledTitle(String title) {
        TitleParts parts = splitTitle(title);
        String label = normalizeLabel(parts.label());
        if (parts.detail().isBlank()) {
            return Tk.toolName(label);
        }
        return Tk.toolName(label) + " " + styledDetail(label, parts.detail());
    }

    private static String styledDetail(String label, String detail) {
        return PATH_DETAIL_LABELS.contains(label)
                ? Tk.filePath(detail)
                : Tk.toolArg(detail);
    }

    private static String inlineSummary(Status status, List<String> summary) {
        if (summary.isEmpty() || summary.getFirst().isBlank()) {
            return "";
        }
        return Tk.dim(" · ") + colorSummary(status, summary.getFirst());
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

    private static String normalizeLabel(String value) {
        String clean = Objects.requireNonNullElse(value, "").strip();
        return switch (clean) {
            case "Read" -> "file_read";
            case "Write" -> "file_write";
            case "Edit" -> "file_edit";
            case "Bash" -> "bash";
            case "Grep" -> "grep";
            case "Glob" -> "glob";
            case "WebFetch" -> "web_fetch";
            case "Skill" -> "skill";
            case "Agent" -> "agent";
            default -> clean.isBlank() ? "tool" : snakeCase(clean);
        };
    }

    private static String snakeCase(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isUpperCase(ch) && i > 0 && out.charAt(out.length() - 1) != '_') {
                out.append('_');
            }
            if (Character.isLetterOrDigit(ch)) {
                out.append(Character.toLowerCase(ch));
            } else if (out.length() > 0 && out.charAt(out.length() - 1) != '_') {
                out.append('_');
            }
        }
        while (!out.isEmpty() && out.charAt(out.length() - 1) == '_') {
            out.deleteCharAt(out.length() - 1);
        }
        return out.isEmpty() ? "tool" : out.toString();
    }

    private static String colorSummary(Status status, String text) {
        return switch (status) {
            case FAILED, DENIED -> Tk.failure(text);
            case RUNNING -> Tk.dim(text);
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
