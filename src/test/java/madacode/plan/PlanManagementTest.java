package madacode.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import madacode.core.ConversationSession;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

class PlanManagementTest {

    private ConversationSession session;

    @BeforeEach
    void setUp() {
        session = new ConversationSession();
    }

    @Test
    void sessionStartsWithNoPlanItems() {
        assertTrue(session.plan().items().isEmpty());
    }

    @Test
    void addAndRetrievePlanItem() {
        PlanItem item = PlanItem.create(session.plan().nextId(), "Setup", "Initial setup", List.of());
        session.plan().add(item);

        assertEquals(1, session.plan().items().size());
        assertEquals(item, session.plan().find(item.id()).orElseThrow());
    }

    @Test
    void updatePlanItemChangesState() {
        PlanItem item = PlanItem.create(session.plan().nextId(), "Task A", "Do A", List.of());
        session.plan().add(item);

        PlanItem next = item.transitionTo(PlanStatus.IN_PROGRESS);
        session.plan().update(next);

        assertEquals(PlanStatus.IN_PROGRESS, session.plan().find(item.id()).orElseThrow().status());
    }

    @Test
    void nextTaskIdIncrements() {
        assertEquals("1", session.plan().nextId());
        session.plan().add(PlanItem.create(session.plan().nextId(), "One", "", List.of()));
        assertEquals("2", session.plan().nextId());
        session.plan().add(PlanItem.create(session.plan().nextId(), "Two", "", List.of()));
        assertEquals("3", session.plan().nextId());
    }

    @Test
    void dependencyBlocking() {
        PlanItem dep = PlanItem.create(session.plan().nextId(), "Dep", "Required first", List.of());
        PlanItem main = PlanItem.create(session.plan().nextId(), "Main", "Depends on Dep", List.of(dep.id()));
        session.plan().add(dep);
        session.plan().add(main);

        Set<String> blockers = session.plan().validateCanStart(main);
        assertFalse(blockers.isEmpty());
        assertTrue(blockers.contains(dep.id()));
    }

    @Test
    void dependencySatisfied() {
        PlanItem dep = PlanItem.create(session.plan().nextId(), "Dep", "", List.of());
        session.plan().add(dep);
        session.plan().update(dep.transitionTo(PlanStatus.IN_PROGRESS));
        session.plan().update(session.plan().find(dep.id()).orElseThrow().transitionTo(PlanStatus.COMPLETED));

        PlanItem main = PlanItem.create(session.plan().nextId(), "Main", "", List.of(dep.id()));
        session.plan().add(main);

        assertTrue(session.plan().validateCanStart(main).isEmpty());
    }

    @Test
    void noDependenciesCanAlwaysStart() {
        PlanItem item = PlanItem.create(session.plan().nextId(), "Solo", "No deps", List.of());
        session.plan().add(item);
        assertTrue(session.plan().validateCanStart(item).isEmpty());
    }

    @Test
    void missingDependencyIsABlocker() {
        PlanItem item = PlanItem.create(session.plan().nextId(), "Main", "", List.of("nonexistent"));
        session.plan().add(item);
        assertEquals(Set.of("nonexistent"), session.plan().validateCanStart(item));
    }

    @Test
    void noCyclicDependencyDetected() {
        PlanItem a = PlanItem.create("1", "A", "", List.of());
        session.plan().add(a);

        PlanItem b = PlanItem.create("2", "B", "", List.of("1"));
        session.plan().add(b);

        assertTrue(session.plan().hasCyclicDependency(a, b.id()));
    }

    @Test
    void noCycleForUnrelatedTasks() {
        PlanItem a = PlanItem.create("1", "A", "", List.of());
        PlanItem b = PlanItem.create("2", "B", "", List.of());
        session.plan().add(a);
        session.plan().add(b);

        assertFalse(session.plan().hasCyclicDependency(a, b.id()));
        assertFalse(session.plan().hasCyclicDependency(b, a.id()));
    }

    @Test
    void noCycleForMissingTarget() {
        PlanItem a = PlanItem.create("1", "A", "", List.of());
        session.plan().add(a);
        assertFalse(session.plan().hasCyclicDependency(a, "nonexistent"));
    }

    @Test
    void fullPlanItemLifecycle() {
        PlanItem item = PlanItem.create(session.plan().nextId(), "Feature X",
                "Implement feature X", List.of());
        session.plan().add(item);
        assertEquals(PlanStatus.PENDING, item.status());

        PlanItem inProgress = item.transitionTo(PlanStatus.IN_PROGRESS);
        session.plan().update(inProgress);

        PlanItem completed = session.plan().find(item.id()).orElseThrow()
                .transitionTo(PlanStatus.COMPLETED);
        session.plan().update(completed);
        assertEquals(PlanStatus.COMPLETED,
                session.plan().find(item.id()).orElseThrow().status());
    }

    @Test
    void replaceTodosClearsPrevious() {
        session.plan().replaceTodos(List.of(
                new TodoItem("Step 1", "completed"),
                new TodoItem("Step 2", "in_progress")));
        assertEquals(2, session.plan().todos().size());

        session.plan().replaceTodos(List.of(new TodoItem("Step 3", "pending")));
        assertEquals(1, session.plan().todos().size());
        assertEquals("Step 3", session.plan().todos().getFirst().content());
    }

    @Test
    void todoItemInvalidStatus() {
        try {
            new TodoItem("Bad", "invalid");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("invalid"));
        }
    }

    @Test
    void todoItemValidStatuses() {
        new TodoItem("P", "pending");
        new TodoItem("IP", "in_progress");
        new TodoItem("C", "completed");
    }

    @Test
    void emptyTodoItemContentIsAllowed() {
        TodoItem item = new TodoItem("", "pending");
        assertEquals("", item.content());
    }
}
