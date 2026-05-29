package madacode.cli.session;

import madacode.core.SessionStorage;
import madacode.core.SessionStorage.SessionSummary;
import madacode.core.SessionStorageException;

import java.util.List;
import java.util.Objects;

public final class SessionListings {

    private SessionListings() {}

    public static List<SessionSummary> recent(SessionStorage storage, int limit) {
        Objects.requireNonNull(storage, "storage");
        if (limit <= 0) {
            return List.of();
        }
        try {
            return storage.listSessions().stream().limit(limit).toList();
        } catch (SessionStorageException e) {
            return List.of();
        }
    }
}
