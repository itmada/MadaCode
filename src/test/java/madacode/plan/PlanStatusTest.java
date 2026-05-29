package madacode.plan;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlanStatusTest {

    @Test
    void anyTransitionAllowed() {
        assertTrue(PlanStatus.PENDING.canTransitionTo(PlanStatus.IN_PROGRESS));
        assertTrue(PlanStatus.PENDING.canTransitionTo(PlanStatus.COMPLETED));
        assertTrue(PlanStatus.IN_PROGRESS.canTransitionTo(PlanStatus.COMPLETED));
        assertTrue(PlanStatus.IN_PROGRESS.canTransitionTo(PlanStatus.PENDING));
        assertTrue(PlanStatus.COMPLETED.canTransitionTo(PlanStatus.PENDING));
        assertTrue(PlanStatus.COMPLETED.canTransitionTo(PlanStatus.IN_PROGRESS));
    }

    @Test
    void nullTransitionRejected() {
        assertFalse(PlanStatus.PENDING.canTransitionTo(null));
    }

    @Test
    void isTerminal() {
        assertFalse(PlanStatus.PENDING.isTerminal());
        assertFalse(PlanStatus.IN_PROGRESS.isTerminal());
        assertTrue(PlanStatus.COMPLETED.isTerminal());
    }

    @Test
    void transitionUpdatesTimestamp() {
        PlanItem item = PlanItem.create("1", "Test", "desc", java.util.List.of());
        PlanItem next = item.transitionTo(PlanStatus.IN_PROGRESS);
        assertEquals(PlanStatus.IN_PROGRESS, next.status());
        assertNotNull(next.updatedAt());
    }

    @Test
    void transitionInvalidThrows() {
        PlanItem item = PlanItem.create("1", "Test", "desc", java.util.List.of());
        assertThrows(IllegalArgumentException.class,
                () -> item.transitionTo(null));
    }

    @Test
    void createSetsDefaults() {
        PlanItem item = PlanItem.create("5", "My task", "Do something", java.util.List.of("1", "2"));
        assertEquals("5", item.id());
        assertEquals("My task", item.title());
        assertEquals("Do something", item.description());
        assertEquals(PlanStatus.PENDING, item.status());
        assertEquals(java.util.List.of("1", "2"), item.blockedBy());
        assertNotNull(item.createdAt());
        assertNotNull(item.updatedAt());
        assertEquals("", item.activeForm());
    }
}
