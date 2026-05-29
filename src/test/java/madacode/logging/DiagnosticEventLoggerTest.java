package madacode.logging;

import madacode.core.ConversationSession;
import madacode.events.AppEvents;
import madacode.events.DefaultAppEventPublisher;
import madacode.events.DiagnosticEvent;
import madacode.events.sinks.AuditSink;
import madacode.events.sinks.DiagnosticSink;
import madacode.events.sinks.FatalStderrSink;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class DiagnosticEventLoggerTest {

    @TempDir
    Path tempDir;

    @Test
    void diagnosticLoggerPublishesThroughInstalledEventPublisher() throws Exception {
        Path logPath = tempDir.resolve("diagnostic.log");
        DefaultAppEventPublisher publisher = new DefaultAppEventPublisher(
                new DiagnosticSink() {
                    @Override
                    public void accept(DiagnosticEvent event) {
                        try {
                            Files.writeString(logPath, event.message() + System.lineSeparator(),
                                    java.nio.file.StandardOpenOption.CREATE,
                                    java.nio.file.StandardOpenOption.APPEND);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                },
                new AuditSink(tempDir.resolve("audit.jsonl")),
                new FatalStderrSink(new PrintStream(java.io.OutputStream.nullOutputStream())),
                16,
                16,
                new PrintStream(java.io.OutputStream.nullOutputStream()),
                null,
                () -> null);
        try {
            AppEvents.install(publisher);

            ConversationSession session = new ConversationSession(tempDir.resolve("workspace"));

            DiagnosticEventLogger.turnStarted(session, 7);
            publisher.flush(Duration.ofSeconds(1));

            assertTrue(Files.isRegularFile(logPath));
            assertTrue(Files.readString(logPath).contains("turn_started"));
        } finally {
            publisher.close();
            AppEvents.resetForTests();
        }
    }
}
