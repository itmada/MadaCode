package madacode.cli.session;

import madacode.core.session.SessionStorage.SessionSummary;

import java.util.List;
import java.util.Optional;

@FunctionalInterface
public interface SessionChooser {

    Optional<String> chooseSession(List<SessionSummary> sessions, String currentSessionId);
}
