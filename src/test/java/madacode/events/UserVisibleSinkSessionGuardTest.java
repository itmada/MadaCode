package madacode.events;

import madacode.events.sinks.UserVisibleSink;
import madacode.tui.TextScreen;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserVisibleSinkSessionGuardTest {

    @Test
    void hiddenSessionEventsAreDowngradedToDiagnostic() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<DiagnosticEvent> hidden = new ArrayList<>();
        UserVisibleSink sink = new UserVisibleSink(
                new TextScreen(new PrintStream(out)),
                new PrintStream(ByteArrayOutputStream.nullOutputStream()),
                () -> "foreground",
                hidden::add);

        sink.accept(UserVisibleEvent.info(
                new EventContext("background", null, null, "test"), "secret"));

        assertEquals("", out.toString());
        assertEquals(1, hidden.size());
        assertTrue(hidden.getFirst().message().contains("secret"));
    }
}
