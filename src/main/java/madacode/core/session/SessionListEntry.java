package madacode.core.session;

import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.model.MessageRole;
import madacode.core.model.MetaEvent;
import madacode.core.model.TokenUsage;

import java.nio.file.Path;
import java.time.Instant;

/**
 * An entry in the session listing. Either a successfully-parsed
 * {@link SessionStorage.SessionSummary} or a {@link Corrupted} file that
 * could not be read.
 *
 * <p>This sealed hierarchy lets callers pattern-match on the two cases
 * and decide how to present them — e.g. the {@code /sessions} slash
 * command shows corrupted entries with a warning so the user knows the
 * file exists and can take action (delete, investigate).
 */
public sealed interface SessionListEntry
        permits SessionStorage.SessionSummary, SessionListEntry.Corrupted {

    Path path();
    Instant lastModifiedAt();

    record Corrupted(
            Path path,
            Instant lastModifiedAt,
            String reason) implements SessionListEntry {}
}
