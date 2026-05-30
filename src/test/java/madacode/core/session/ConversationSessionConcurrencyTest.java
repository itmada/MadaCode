package madacode.core.session;

import madacode.core.model.*;
import madacode.core.turn.*;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bug 5 regression: state reads from background threads must observe stable
 * immutable snapshots while the main thread mutates state.
 */
class ConversationSessionConcurrencyTest {

    @Test
    void concurrentReadersSeeStableSnapshots() throws Exception {
        ConversationSession session = new ConversationSession();
        final int writes = 500;

        ExecutorService readers = Executors.newFixedThreadPool(4);
        AtomicReference<Throwable> readerFailure = new AtomicReference<>();
        CountDownLatch stop = new CountDownLatch(1);

        Runnable reader = () -> {
            try {
                while (stop.getCount() > 0) {
                    List<Message> snapshot = session.messages();
                    // Iterate the snapshot fully. If the underlying list were
                    // shared with the writer thread we'd risk
                    // ConcurrentModificationException or torn reads.
                    int seen = 0;
                    for (Message m : snapshot) {
                        if (m == null) {
                            throw new AssertionError("null message in snapshot at index " + seen);
                        }
                        seen++;
                    }
                    assertEquals(snapshot.size(), seen, "iteration count mismatch");
                }
            } catch (Throwable t) {
                readerFailure.compareAndSet(null, t);
            }
        };
        for (int i = 0; i < 4; i++) readers.submit(reader);

        // Writer: alternate USER and ASSISTANT to satisfy the no-consecutive-same-role invariant.
        for (int i = 0; i < writes; i++) {
            session.addMessage((i % 2 == 0)
                    ? Message.user("u" + i)
                    : Message.assistant("a" + i));
        }

        stop.countDown();
        readers.shutdown();
        assertTrue(readers.awaitTermination(10, TimeUnit.SECONDS), "readers did not stop");
        assertNull(readerFailure.get(), () ->
                "reader failed: " + readerFailure.get());

        // Final assertion: no messages lost.
        List<Message> finalSnapshot = session.messages();
        // 1 (initial system) + writes
        assertEquals(1 + writes, finalSnapshot.size());
    }

    @Test
    void snapshotIsTrulyImmutable() {
        ConversationSession session = new ConversationSession();
        session.addMessage(Message.user("hi"));
        List<Message> snapshot = session.messages();
        // List.copyOf returns an unmodifiable list.
        assertTrue(snapshot.getClass().getName().contains("Immutable")
                        || throwsUOE(() -> snapshot.add(Message.assistant("x"))),
                "snapshot must be immutable");
    }

    @Test
    void tokenUsageUpdatesAreAtomicUnderConcurrency() throws Exception {
        // Multiple threads fire TokenReport concurrently — the running total
        // must equal the sum of individual deltas (no lost updates).
        ConversationSession session = new ConversationSession();
        int threads = 8;
        int reportsPerThread = 250;
        int inputPerReport = 3;
        int outputPerReport = 5;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    for (int i = 0; i < reportsPerThread; i++) {
                        TokenUsage delta = new TokenUsage(inputPerReport, outputPerReport, 0, 0);
                        session.fireMetaEvent(new MetaEvent.TokenReport(delta, 0, 0));
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        assertTrue(done.await(15, TimeUnit.SECONDS));
        pool.shutdown();

        TokenUsage total = session.tokenUsage();
        int expectedTotalIn = threads * reportsPerThread * inputPerReport;
        int expectedTotalOut = threads * reportsPerThread * outputPerReport;
        assertEquals(expectedTotalIn, total.inputTokens(), "input tokens lost an update");
        assertEquals(expectedTotalOut, total.outputTokens(), "output tokens lost an update");
    }

    @Test
    void distinctSnapshotsRemainConsistent() {
        // Capture two snapshots at different times. Both must remain valid and
        // self-consistent forever — even after further mutations.
        ConversationSession session = new ConversationSession();
        session.addMessage(Message.user("first"));
        List<Message> s1 = session.messages();
        int s1Size = s1.size();
        Set<Message> s1Contents = new HashSet<>(s1);

        session.addMessage(Message.assistant("a1"));
        session.addMessage(Message.user("second"));
        session.addMessage(Message.assistant("a2"));

        // s1 must still report the size and contents it had at capture time.
        assertEquals(s1Size, s1.size(), "old snapshot mutated");
        assertEquals(s1Contents, new HashSet<>(s1), "old snapshot contents drifted");
    }

    private static boolean throwsUOE(Runnable r) {
        try { r.run(); return false; }
        catch (UnsupportedOperationException e) { return true; }
    }
}
