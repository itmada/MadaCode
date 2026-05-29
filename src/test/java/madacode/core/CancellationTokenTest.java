package madacode.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancellationTokenTest {

    @Test
    void freshTokenIsNotCancelled() {
        CancellationToken t = CancellationToken.create();
        assertFalse(t.isCancelled());
        assertNull(t.reason());
    }

    @Test
    void cancelSetsStateAndReason() {
        CancellationToken t = CancellationToken.create();
        t.cancel("user typed exit");
        assertTrue(t.isCancelled());
        assertEquals("user typed exit", t.reason());
    }

    @Test
    void firstReasonWinsAndCallbacksFireOnce() {
        CancellationToken t = CancellationToken.create();
        List<String> fired = new ArrayList<>();
        t.onCancel(() -> fired.add("a"));
        t.onCancel(() -> fired.add("b"));

        t.cancel("first");
        t.cancel("second");

        assertEquals("first", t.reason());
        assertEquals(List.of("a", "b"), fired);
    }

    @Test
    void callbackRegisteredAfterCancelFiresImmediately() {
        CancellationToken t = CancellationToken.create();
        t.cancel("done");

        List<String> fired = new ArrayList<>();
        t.onCancel(() -> fired.add("late"));

        assertEquals(List.of("late"), fired);
    }

    @Test
    void misbehavingCallbackDoesntBlockOthers() {
        CancellationToken t = CancellationToken.create();
        List<String> fired = new ArrayList<>();
        t.onCancel(() -> { throw new RuntimeException("boom"); });
        t.onCancel(() -> fired.add("ran anyway"));

        t.cancel(null);
        assertEquals(List.of("ran anyway"), fired);
    }

    @Test
    void throwIfCancelledBehavior() {
        CancellationToken t = CancellationToken.create();
        t.throwIfCancelled(); // no-op while not cancelled
        t.cancel("stop");
        CancellationException e = assertThrows(CancellationException.class, t::throwIfCancelled);
        assertEquals("stop", e.getMessage());
    }

    @Test
    void subscriptionCloseRemovesCallbackBeforeCancel() {
        // Bug 4 regression: a subscription returned by onCancel must let the
        // registrar withdraw the callback before cancel fires.
        CancellationToken t = CancellationToken.create();
        boolean[] fired = { false };
        Subscription sub = t.onCancel(() -> fired[0] = true);
        sub.close();
        t.cancel("now");
        assertFalse(fired[0], "withdrawn callback must not fire");
    }

    @Test
    void multipleSubscriptionsAreIndependent() {
        CancellationToken t = CancellationToken.create();
        List<Integer> fires = new ArrayList<>();
        Subscription a = t.onCancel(() -> fires.add(1));
        Subscription b = t.onCancel(() -> fires.add(2));
        Subscription c = t.onCancel(() -> fires.add(3));

        b.close();   // withdraw the middle one
        t.cancel("go");

        assertEquals(List.of(1, 3), fires, "1 and 3 fire in order, 2 was withdrawn");
        // Closing after cancel is harmless (idempotent).
        a.close();
        c.close();
    }

    @Test
    void subscriptionCloseAfterCancelIsNoop() {
        CancellationToken t = CancellationToken.create();
        Subscription sub = t.onCancel(() -> {});
        t.cancel("now");
        sub.close();   // must not throw, must not do anything weird
        // Calling close again is also fine.
        sub.close();
    }

    @Test
    void onCancelAfterAlreadyCancelledFiresImmediatelyAndReturnsNoopSub() {
        CancellationToken t = CancellationToken.create();
        t.cancel("first");
        boolean[] fired = { false };
        Subscription sub = t.onCancel(() -> fired[0] = true);
        assertTrue(fired[0], "callback registered after cancel must fire immediately");
        sub.close();   // no-op
    }

    @Test
    void neverTokenSubscriptionIsNoop() {
        // Bug 4: NEVER must not leak — onCancel returns a no-op subscription.
        boolean[] fired = { false };
        Subscription sub = CancellationToken.never().onCancel(() -> fired[0] = true);
        sub.close();
        // never() cannot cancel; nothing should have fired.
        assertFalse(fired[0]);
    }

    @Test
    void neverTokenStaysUncancellable() {
        CancellationToken t = CancellationToken.never();
        assertFalse(t.isCancelled());
        // Calling cancel() on the shared NEVER singleton must be a no-op,
        // otherwise one test could poison every other caller that uses
        // CancellationToken.never() as a default.
        t.cancel("should be ignored");
        assertFalse(CancellationToken.never().isCancelled());
        assertNull(CancellationToken.never().reason());
    }
}
