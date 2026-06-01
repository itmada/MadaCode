package madacode.core.session;

import madacode.permission.PermissionMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionModeTest {

    @Test
    void applyToSetsWorkflowAxisOnly() {
        ConversationSession session = new ConversationSession();
        session.setPermissionMode(PermissionMode.ACCEPT_EDITS);
        session.setPlanMode(true);

        SessionMode.LONG_RUNNING.applyTo(session);
        assertEquals(SessionMode.LONG_RUNNING, session.workflowMode());
        assertEquals(PermissionMode.ACCEPT_EDITS, session.permissionMode());
        assertTrue(session.isPlanMode());
    }

    @Test
    void fromReturnsWorkflowModeEvenWhenPlanModeActive() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setPermissionMode(PermissionMode.BYPASS);
        session.setPlanMode(true);

        assertEquals(SessionMode.LONG_RUNNING, SessionMode.from(session));
    }

    @Test
    void parseAcceptsPublicNames() {
        assertEquals(SessionMode.COMMON, SessionMode.parse("common").orElseThrow());
        assertEquals(SessionMode.LONG_RUNNING, SessionMode.parse("long_running").orElseThrow());
        assertTrue(SessionMode.parse("missing").isEmpty());
    }

    @Test
    void newSessionDefaultsToCommonWorkflowState() {
        ConversationSession session = new ConversationSession();

        assertEquals(SessionMode.COMMON, session.workflowMode());
        assertNull(session.longRunningStage());
        assertNull(session.longRunningTaskId());
        assertNull(session.longRunningTaskDirectory());
    }

    @Test
    void commonWorkflowRejectsLongRunningStateFields() {
        ConversationSession session = new ConversationSession();

        assertThrows(IllegalStateException.class,
                () -> session.setLongRunningStage(LongRunningStage.PLANNING));
        assertThrows(IllegalStateException.class,
                () -> session.setLongRunningTaskId("task-1"));
        assertThrows(IllegalStateException.class,
                () -> session.setLongRunningTaskDirectory("/tmp/task-1"));
    }
}
