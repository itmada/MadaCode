package madacode.events;

import madacode.events.sinks.AuditSink;
import madacode.events.sinks.DiagnosticSink;
import madacode.events.sinks.FatalStderrSink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecursionGuardTest {

    @TempDir
    Path tempDir;

    @Test
    void sinkInternalPublishDoesNotReenterDispatcher() {
        AtomicInteger calls = new AtomicInteger();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        DefaultAppEventPublisher[] holder = new DefaultAppEventPublisher[1];
        DiagnosticSink recursiveSink = new DiagnosticSink() {
            @Override
            public void accept(DiagnosticEvent event) {
                calls.incrementAndGet();
                holder[0].publish(DiagnosticEvent.info(EventContext.bootstrap("test"), "nested"));
            }
        };
        holder[0] = new DefaultAppEventPublisher(
                recursiveSink,
                new AuditSink(tempDir.resolve("audit.jsonl")),
                new FatalStderrSink(new PrintStream(err)),
                16,
                16,
                new PrintStream(err),
                null,
                () -> null);
        try {
            holder[0].publish(DiagnosticEvent.info(EventContext.bootstrap("test"), "outer"));
            holder[0].flush(Duration.ofSeconds(1));
            assertEquals(1, calls.get());
        } finally {
            holder[0].close();
        }
    }
}
