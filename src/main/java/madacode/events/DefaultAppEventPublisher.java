package madacode.events;

import madacode.events.sinks.AuditSink;
import madacode.events.sinks.DiagnosticSink;
import madacode.events.sinks.FatalStderrSink;
import madacode.events.sinks.UserVisibleSink;
import madacode.tui.Screen;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class DefaultAppEventPublisher implements AppEventPublisher {

    public static final int DEFAULT_DIAGNOSTIC_CAPACITY = 1024;
    public static final int DEFAULT_AUDIT_CAPACITY = 8192;

    private static final Duration DEFAULT_FATAL_FLUSH_TIMEOUT = Duration.ofSeconds(2);
    private static final ThreadLocal<Boolean> IN_SINK = ThreadLocal.withInitial(() -> false);

    private final UserVisibleSink userSink;
    private final DiagnosticSink diagSink;
    private final AuditSink auditSink;
    private final FatalStderrSink fatalStderr;
    private final BlockingQueue<DiagnosticEvent> diagQueue;
    private final BlockingQueue<AuditEvent> auditQueue;
    private final AtomicLong diagAccepted = new AtomicLong();
    private final AtomicLong diagCompleted = new AtomicLong();
    private final AtomicLong auditAccepted = new AtomicLong();
    private final AtomicLong auditCompleted = new AtomicLong();
    private final Thread diagDispatcher;
    private final Thread auditDispatcher;
    private final PrintStream err;
    private volatile boolean closing;

    public DefaultAppEventPublisher(
            Screen screen,
            Supplier<String> foregroundSessionId,
            Path auditPath) {
        this(
                new DiagnosticSink(),
                new AuditSink(auditPath),
                new FatalStderrSink(System.err),
                DEFAULT_DIAGNOSTIC_CAPACITY,
                DEFAULT_AUDIT_CAPACITY,
                System.err,
                screen,
                foregroundSessionId);
    }

    public DefaultAppEventPublisher(
            DiagnosticSink diagSink,
            AuditSink auditSink,
            FatalStderrSink fatalStderr,
            int diagCapacity,
            int auditCapacity,
            PrintStream err,
            Screen screen,
            Supplier<String> foregroundSessionId) {
        this.diagSink = Objects.requireNonNull(diagSink, "diagSink");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.fatalStderr = Objects.requireNonNull(fatalStderr, "fatalStderr");
        this.err = Objects.requireNonNull(err, "err");
        Consumer<DiagnosticEvent> hiddenUserEvents = this::enqueueDiagnostic;
        this.userSink = new UserVisibleSink(screen, err, foregroundSessionId, hiddenUserEvents);
        this.diagQueue = new ArrayBlockingQueue<>(Math.max(1, diagCapacity));
        this.auditQueue = new LinkedBlockingQueue<>(Math.max(1, auditCapacity));
        this.diagDispatcher = new Thread(this::drainDiagnostics, "mada-events-diagnostic");
        this.auditDispatcher = new Thread(this::drainAudits, "mada-events-audit");
        this.diagDispatcher.setDaemon(true);
        this.auditDispatcher.setDaemon(true);
        this.diagDispatcher.start();
        this.auditDispatcher.start();
    }

    @Override
    public void publish(AppEvent event) {
        Objects.requireNonNull(event, "event");
        if (closing) {
            EventFallback.write(event, err);
            return;
        }
        if (Boolean.TRUE.equals(IN_SINK.get())) {
            EventFallback.write(event, err);
            return;
        }
        switch (event) {
            case UserVisibleEvent u -> runSync(() -> userSink.accept(u));
            case DiagnosticEvent d -> enqueueDiagnostic(d);
            case AuditEvent a -> enqueueAudit(a);
            case FatalEvent f -> {
                runSync(() -> fatalStderr.accept(f));
                runSync(() -> userSink.accept(new UserVisibleEvent(
                        f.timestamp(),
                        f.sequence(),
                        f.context(),
                        UserVisibleEvent.Level.ERROR,
                        f.message(),
                        f.error())));
                flush(DEFAULT_FATAL_FLUSH_TIMEOUT);
            }
        }
    }

    @Override
    public long nextSequence() {
        return AppEvents.nextSequence();
    }

    @Override
    public void flush(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long targetDiagnostics = diagAccepted.get();
        long targetAudits = auditAccepted.get();
        while (System.nanoTime() < deadline) {
            if (diagCompleted.get() >= targetDiagnostics && auditCompleted.get() >= targetAudits) {
                break;
            }
            sleepQuietly(10);
        }
        runSync(() -> diagSink.flush(timeout));
        runSync(() -> auditSink.flush(timeout));
        runSync(() -> userSink.flush(timeout));
        runSync(() -> fatalStderr.flush(timeout));
    }

    @Override
    public void close() {
        closing = true;
        flush(Duration.ofSeconds(2));
        diagDispatcher.interrupt();
        auditDispatcher.interrupt();
        joinQuietly(diagDispatcher);
        joinQuietly(auditDispatcher);
        runSync(diagSink::close);
        runSync(auditSink::close);
        runSync(userSink::close);
        runSync(fatalStderr::close);
        AppEvents.install(new BootstrapFallbackPublisher(err));
    }

    private void enqueueDiagnostic(DiagnosticEvent event) {
        diagAccepted.incrementAndGet();
        if (!diagQueue.offer(event)) {
            DiagnosticEvent discarded = diagQueue.poll();
            if (discarded != null) {
                diagCompleted.incrementAndGet();
            }
            if (!diagQueue.offer(event)) {
                diagCompleted.incrementAndGet();
            }
        }
    }

    private void enqueueAudit(AuditEvent event) {
        auditAccepted.incrementAndGet();
        try {
            auditQueue.put(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            EventFallback.write(event, err);
            auditCompleted.incrementAndGet();
        }
    }

    private void drainDiagnostics() {
        while (!closing || !diagQueue.isEmpty()) {
            try {
                DiagnosticEvent event = diagQueue.poll(250, TimeUnit.MILLISECONDS);
                if (event == null) {
                    continue;
                }
                runSync(() -> diagSink.accept(event));
                diagCompleted.incrementAndGet();
            } catch (InterruptedException e) {
                if (closing) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (RuntimeException e) {
                EventFallback.writeFailure("diagnostic dispatcher failure", e, err);
            }
        }
    }

    private void drainAudits() {
        while (!closing || !auditQueue.isEmpty()) {
            try {
                AuditEvent event = auditQueue.poll(250, TimeUnit.MILLISECONDS);
                if (event == null) {
                    continue;
                }
                runSync(() -> auditSink.accept(event));
                auditCompleted.incrementAndGet();
            } catch (InterruptedException e) {
                if (closing) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (RuntimeException e) {
                EventFallback.writeFailure("audit dispatcher failure", e, err);
            }
        }
    }

    private void runSync(Runnable r) {
        boolean previous = Boolean.TRUE.equals(IN_SINK.get());
        IN_SINK.set(true);
        try {
            r.run();
        } catch (Throwable t) {
            EventFallback.writeFailure("sink failure", t, err);
        } finally {
            IN_SINK.set(previous);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public DiagnosticEvent diagnosticFallback(EventContext context, String message) {
        return new DiagnosticEvent(
                Instant.now(),
                nextSequence(),
                context,
                Severity.DEBUG,
                message,
                null);
    }
}
