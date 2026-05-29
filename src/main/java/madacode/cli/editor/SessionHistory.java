package madacode.cli.editor;

import org.jline.reader.impl.history.DefaultHistory;

import java.time.Instant;
import java.util.List;

/**
 * JLine {@link org.jline.reader.History} backed by the current session's input list.
 *
 * <p>Call {@link #reset(List)} before each {@code readLine()} to synchronise
 * from the session.  No file persistence — session storage is the source of
 * truth.
 */
public final class SessionHistory extends DefaultHistory {

    public void reset(List<String> entries) {
        try {
            purge();
        } catch (Exception ignored) {
        }
        if (entries != null) {
            for (String e : entries) {
                if (e != null && !e.isBlank()) {
                    internalAdd(Instant.now(), e, false);
                }
            }
        }
        moveToEnd();
    }
}
