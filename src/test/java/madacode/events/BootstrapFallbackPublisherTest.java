package madacode.events;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapFallbackPublisherTest {

    @Test
    void userVisibleAndFatalEventsUseStderrBeforeInstall() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        BootstrapFallbackPublisher publisher = new BootstrapFallbackPublisher(new PrintStream(err));

        publisher.publish(UserVisibleEvent.info(EventContext.bootstrap("test"), "hello"));
        publisher.publish(DiagnosticEvent.debug(EventContext.bootstrap("test"), "debug detail"));
        publisher.publish(DiagnosticEvent.info(EventContext.bootstrap("test"), "info detail"));
        publisher.publish(DiagnosticEvent.warn(EventContext.bootstrap("test"), "diagnostic warning"));
        publisher.publish(FatalEvent.create(EventContext.bootstrap("test"), "boom", null, 1));

        String output = err.toString();
        assertTrue(output.contains("hello"));
        assertFalse(output.contains("debug detail"));
        assertFalse(output.contains("info detail"));
        assertTrue(output.contains("diagnostic warning"));
        assertTrue(output.contains("[FATAL] boom"));
    }
}
