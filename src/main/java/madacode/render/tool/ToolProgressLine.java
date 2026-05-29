package madacode.render.tool;

import java.util.Objects;

public record ToolProgressLine(
        Kind kind,
        String text,
        long timestampMs
) {

    public enum Kind {
        ACTIVITY,
        OUTPUT,
        METRIC,
        HOOK
    }

    public ToolProgressLine {
        kind = Objects.requireNonNull(kind, "kind");
        text = sanitize(text);
    }

    public static ToolProgressLine activity(String text) {
        return new ToolProgressLine(Kind.ACTIVITY, text, System.currentTimeMillis());
    }

    public static ToolProgressLine output(String text) {
        return new ToolProgressLine(Kind.OUTPUT, text, System.currentTimeMillis());
    }

    public static ToolProgressLine metric(String text) {
        return new ToolProgressLine(Kind.METRIC, text, System.currentTimeMillis());
    }

    public static ToolProgressLine hook(String text) {
        return new ToolProgressLine(Kind.HOOK, text, System.currentTimeMillis());
    }

    private static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        return text.replace('\n', ' ').replace('\r', ' ').strip();
    }
}
