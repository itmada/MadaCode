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

    private final SessionStorage storage;
    private final BufferedReader in;
    private final PrintStream out;

    public SessionPicker(SessionStorage storage, BufferedReader in, PrintStream out) {
        this.storage = storage;
        this.in = in;
        this.out = out;
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
        out.println("Recent sessions:");
        for (int i = 0; i < recent.size(); i++) {
            SessionSummary s = recent.get(i);
            out.printf("  [%d] %s  %-50s  (%d messages)%n",
                    i + 1,
                    DATE_FMT.format(s.lastModifiedAt()),
                    s.sessionId(),
                    s.messageCount());
        }
        out.println("  [N] New session");
        out.println("  [Q] Quit");

        while (true) {
            out.print("> ");
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

    public sealed interface PickResult {
        record Resume(String sessionId) implements PickResult {}
        record New() implements PickResult {}
    }
}
