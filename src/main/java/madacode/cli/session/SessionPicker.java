package madacode.cli.session;

import madacode.core.session.SessionStorage;
import madacode.core.session.SessionStorage.SessionSummary;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class SessionPicker {

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private static final int MAX_RECENT = 5;
    private static final int DEFAULT_WIDTH = 80;

    private final SessionStorage storage;
    private final BufferedReader in;
    private final PrintStream out;
    private final int width;

    public SessionPicker(SessionStorage storage, BufferedReader in, PrintStream out) {
        this(storage, in, out, DEFAULT_WIDTH);
    }

    public SessionPicker(SessionStorage storage, BufferedReader in, PrintStream out, int width) {
        this.storage = storage;
        this.in = in;
        this.out = out;
        this.width = Math.max(20, width);
    }

    public PickResult pick() {
        List<SessionSummary> recent = SessionListings.recent(storage, MAX_RECENT);

        if (recent.isEmpty()) {
            out.println("No recent sessions found — starting new session.");
            return new PickResult.New();
        }

        return promptUser(recent);
    }

    private PickResult promptUser(List<SessionSummary> recent) {
        out.println();
        out.println(divider("Session", width));
        for (int i = 0; i < recent.size(); i++) {
            SessionSummary s = recent.get(i);
            String prefix = "  [" + (i + 1) + "] ";
            out.printf("  [%d] %s%n",
                    i + 1,
                    fitLine(prefix, plainLabel(s), width));
        }
        out.println("  [N] New session");
        out.println("  [Q] Quit");

        while (true) {
            out.print("› ");
            out.flush();
            String line;
            try {
                line = in.readLine();
            } catch (IOException e) {
                return new PickResult.New();
            }
            if (line == null) {
                return null; // EOF
            }
            String trimmed = line.trim();
            if (trimmed.equalsIgnoreCase("N")) {
                return new PickResult.New();
            }
            if (trimmed.equalsIgnoreCase("Q")) {
                return null;
            }
            try {
                int index = Integer.parseInt(trimmed);
                if (index >= 1 && index <= recent.size()) {
                    return new PickResult.Resume(recent.get(index - 1).sessionId());
                }
            } catch (NumberFormatException ignored) {
            }
            out.println("Invalid choice. Enter 1-" + recent.size() + ", N, or Q.");
        }
    }

    private String plainLabel(SessionSummary summary) {
        String title = SessionChoiceFormatter.title(storage, summary);
        String meta = DATE_FMT.format(summary.lastModifiedAt())
                + "  "
                + SessionChoiceFormatter.shortId(summary.sessionId())
                + "  "
                + summary.messageCount()
                + " messages";
        return title + "  " + meta;
    }

    private static String fitLine(String prefix, String text, int width) {
        int budget = Math.max(1, width - madacode.tui.TerminalText.displayWidth(prefix));
        return madacode.tui.TerminalText.fitEnd(text, budget);
    }

    private static String divider(String title, int width) {
        int safeWidth = Math.max(1, width);
        if (safeWidth == 1) {
            return "─";
        }
        String prefix = "── ";
        int titleBudget = Math.max(0, safeWidth - madacode.tui.TerminalText.displayWidth(prefix) - 1);
        String visibleTitle = madacode.tui.TerminalText.fitEnd(title, titleBudget);
        StringBuilder line = new StringBuilder(prefix).append(visibleTitle);
        int used = madacode.tui.TerminalText.displayWidth(line.toString());
        if (used < safeWidth) {
            line.append(' ');
            used++;
        }
        while (used < safeWidth) {
            line.append('─');
            used++;
        }
        return line.toString();
    }

    public sealed interface PickResult {
        record Resume(String sessionId) implements PickResult {}
        record New() implements PickResult {}
    }
}
