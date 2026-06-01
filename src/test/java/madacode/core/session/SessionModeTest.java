package madacode.core.session;

import madacode.permission.PermissionMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionModeTest {

    @Test
    void applyToSetsBothRuntimeAxes() {
        ConversationSession session = new ConversationSession();

        SessionMode.NORMAL.applyTo(session);
        assertEquals(PermissionMode.ACCEPT_EDITS, session.permissionMode());
        assertFalse(session.isPlanMode());

        SessionMode.PLAN.applyTo(session);
        assertEquals(PermissionMode.DEFAULT, session.permissionMode());
        assertTrue(session.isPlanMode());

        SessionMode.ALL_PASS.applyTo(session);
        assertEquals(PermissionMode.BYPASS, session.permissionMode());
        assertFalse(session.isPlanMode());
    }

    @Test
    void fromPrefersPlanAxisWhenActive() {
        ConversationSession session = new ConversationSession();
        session.setPermissionMode(PermissionMode.BYPASS);
        session.setPlanMode(true);

        assertEquals(SessionMode.PLAN, SessionMode.from(session));
    }

    @Test
    void parseAcceptsPublicNames() {
        assertEquals(SessionMode.STRICT, SessionMode.parse("strict").orElseThrow());
        assertEquals(SessionMode.ALL_PASS, SessionMode.parse("all_pass").orElseThrow());
        assertTrue(SessionMode.parse("missing").isEmpty());
    }

    @Test
    void newSessionDefaultsToCommonWorkflowState() {
        ConversationSession session = new ConversationSession();

        assertEquals(WorkflowMode.COMMON, session.workflowMode());
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
