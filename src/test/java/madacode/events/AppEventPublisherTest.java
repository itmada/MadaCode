package madacode.events;

import madacode.events.sinks.AuditSink;
import madacode.events.sinks.DiagnosticSink;
import madacode.events.sinks.FatalStderrSink;
import madacode.tui.TextScreen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppEventPublisherTest {

    @TempDir
    Path tempDir;

    @Test
    void userVisibleEventsRunSynchronouslyOnCallingThread() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DefaultAppEventPublisher publisher = publisher(
                new RecordingDiagnosticSink(),
                new AuditSink(tempDir.resolve("audit.jsonl")),
                new PrintStream(out),
                new TextScreen(new PrintStream(out)),
                () -> null);
        try {
            publisher.publish(UserVisibleEvent.info(EventContext.bootstrap("test"), "hello"));
            assertTrue(out.toString().contains("hello"));
        } finally {
            publisher.close();
        }
    }

    @Test
    void diagnosticEventsAreConsumedAsynchronously() throws Exception {
        RecordingDiagnosticSink sink = new RecordingDiagnosticSink();
        DefaultAppEventPublisher publisher = publisher(
                sink,
                new AuditSink(tempDir.resolve("audit.jsonl")),
                new PrintStream(ByteArrayOutputStream.nullOutputStream()),
                null,
                () -> null);
        try {
            publisher.publish(DiagnosticEvent.info(EventContext.bootstrap("test"), "queued"));
            assertTrue(sink.await());
            assertEquals(1, sink.events.size());
        } finally {
            publisher.close();
        }
    }

    @Test
    void fatalEventsBypassQueuesAndWriteToStderr() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream errStream = new PrintStream(err);
        DefaultAppEventPublisher publisher = publisher(
                new RecordingDiagnosticSink(),
                new AuditSink(tempDir.resolve("audit.jsonl")),
                errStream,
                null,
                () -> null);
        try {
            publisher.publish(FatalEvent.create(EventContext.bootstrap("test"), "boom", null, 1));
            assertTrue(err.toString().contains("[FATAL] boom"));
        } finally {
            publisher.close();
        }
    }

    @Test
    void closeDrainsQueuedAuditEvents() throws Exception {
        Path auditPath = tempDir.resolve("audit.jsonl");
        DefaultAppEventPublisher publisher = publisher(
                new RecordingDiagnosticSink(),
                new AuditSink(auditPath),
                new PrintStream(ByteArrayOutputStream.nullOutputStream()),
                null,
                () -> null);

        for (int i = 0; i < 250; i++) {
            publisher.publish(AuditEvent.permissionDecision(
                    EventContext.bootstrap("test"),
                    "bash",
                    true,
                    "",
                    "test",
                    i,
                    "{\"i\":" + i + "}"));
        }
        publisher.close();

        assertEquals(250, Files.readAllLines(auditPath).size());
    }

    @Test
    void flushWaitsForInFlightDiagnosticEvents() {
        SlowDiagnosticSink sink = new SlowDiagnosticSink();
        DefaultAppEventPublisher publisher = publisher(
                sink,
                new AuditSink(tempDir.resolve("audit.jsonl")),
                new PrintStream(ByteArrayOutputStream.nullOutputStream()),
                null,
                () -> null);
        try {
            publisher.publish(DiagnosticEvent.info(EventContext.bootstrap("test"), "slow"));
            publisher.flush(Duration.ofSeconds(1));
            assertEquals(1, sink.completed.get());
        } finally {
            publisher.close();
        }
    }

    @Test
    void flushReturnsPromptlyWhenNoAsyncEventsArePending() {
        DefaultAppEventPublisher publisher = publisher(
                new RecordingDiagnosticSink(),
                new AuditSink(tempDir.resolve("audit.jsonl")),
                new PrintStream(ByteArrayOutputStream.nullOutputStream()),
                null,
                () -> null);
        try {
            long started = System.nanoTime();
            publisher.flush(Duration.ofSeconds(2));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis < 200, "idle flush took " + elapsedMillis + "ms");
        } finally {
            publisher.close();
        }
    }

    @Test
    void publishAfterCloseFallsBackInsteadOfBlocking() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream errStream = new PrintStream(err);
        DefaultAppEventPublisher publisher = publisher(
                new RecordingDiagnosticSink(),
                new AuditSink(tempDir.resolve("audit.jsonl")),
                errStream,
                null,
                () -> null);

        publisher.close();
        publisher.publish(AuditEvent.permissionDecision(
                EventContext.bootstrap("test"),
                "bash",
                false,
                "closed",
                "test",
                0,
                "{}"));

        assertTrue(err.toString().contains("[AUDIT]"));
    }

    @Test
    void sequenceIsMonotonic() {
        BootstrapFallbackPublisher publisher = new BootstrapFallbackPublisher(
                new PrintStream(ByteArrayOutputStream.nullOutputStream()));
        long first = publisher.nextSequence();
        long second = publisher.nextSequence();
        assertTrue(second > first);
    }

    @Test
    void sequenceContinuesAcrossPublisherTypes() {
        BootstrapFallbackPublisher fallback = new BootstrapFallbackPublisher(
                new PrintStream(ByteArrayOutputStream.nullOutputStream()));
        long first = fallback.nextSequence();

        DefaultAppEventPublisher publisher = publisher(
                new RecordingDiagnosticSink(),
                new AuditSink(tempDir.resolve("audit.jsonl")),
                new PrintStream(ByteArrayOutputStream.nullOutputStream()),
                null,
                () -> null);
        try {
            assertTrue(publisher.nextSequence() > first);
        } finally {
            publisher.close();
        }
    }

    private DefaultAppEventPublisher publisher(
            DiagnosticSink diagnosticSink,
            AuditSink auditSink,
            PrintStream err,
            madacode.tui.Screen screen,
            java.util.function.Supplier<String> foreground) {
        return new DefaultAppEventPublisher(
                diagnosticSink,
                auditSink,
                new FatalStderrSink(err),
                16,
                16,
                err,
                screen,
                foreground);
    }

    private static final class RecordingDiagnosticSink extends DiagnosticSink {
        private final CountDownLatch latch = new CountDownLatch(1);
        private final List<DiagnosticEvent> events = new ArrayList<>();

        @Override
        public synchronized void accept(DiagnosticEvent event) {
            events.add(event);
            latch.countDown();
        }

        boolean await() throws InterruptedException {
            return latch.await(1, TimeUnit.SECONDS);
        }
    }

    private static final class SlowDiagnosticSink extends DiagnosticSink {
        private final AtomicInteger completed = new AtomicInteger();

        @Override
        public void accept(DiagnosticEvent event) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            completed.incrementAndGet();
        }
    }
}
