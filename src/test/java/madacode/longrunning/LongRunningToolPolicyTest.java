package madacode.longrunning;

import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.tool.Tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.core.model.ToolResult;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunningToolPolicyTest {

    // ---- Visibility tests ----

    @Test
    void taskUpdateHiddenInExecutingControlSession() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);

        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_task_update", session));
    }

    @Test
    void neitherVisibleInCommonMode() {
        ConversationSession session = new ConversationSession();
        // Default is COMMON mode

        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_task_update", session));
    }

    @Test
    void neitherVisibleInCompletedStage() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DONE);

        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_task_update", session));
    }

    @Test
    void neitherVisibleInCancelledStage() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DONE);

        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_task_update", session));
    }

    @Test
    void nonLongRunningToolsAlwaysVisible() {
        ConversationSession session = new ConversationSession();
        assertTrue(LongRunningToolPolicy.isToolVisible("file_read", session));
        assertTrue(LongRunningToolPolicy.isToolVisible("bash", session));

        ConversationSession lrSession = new ConversationSession();
        lrSession.setWorkflowMode(SessionMode.LONG_RUNNING);
        lrSession.setLongRunningStage(LongRunningStage.RUNNING);
        assertTrue(LongRunningToolPolicy.isToolVisible("file_read", lrSession));
    }

    @Test
    void draftControlSessionAllowsOrdinaryTools() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DRAFT);

        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("bash", false), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("write", false), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("edit", false), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("file_read", true), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("plan_create", false), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("plan_get", true), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("plan_list", true), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("plan_update", false), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("todo_write", false), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("enter_plan_mode", true), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("exit_plan_mode", true), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("ask_user_question", false), session));
    }

    @Test
    void runningControlSessionAllowsOrdinaryTools() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);

        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("bash", false), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("write", false), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("edit", false), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("file_read", true), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("plan_create", false), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("plan_get", true), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("plan_list", true), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("plan_update", false), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("todo_write", false), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("enter_plan_mode", true), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("exit_plan_mode", true), session));
        assertTrue(LongRunningToolPolicy.isToolVisible(new StubTool("ask_user_question", false), session));
    }

    // ---- Execution denial tests ----

    @Test
    void taskUpdateDeniedInPlanningStage() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DRAFT);

        String reason = LongRunningToolPolicy.executionDenialReason("longrun_task_update", session);
        assertNotNull(reason);
        assertTrue(reason.contains("worker session"));
    }

    @Test
    void taskUpdateDeniedInWaitingForApprovalStage() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DRAFT);

        String reason = LongRunningToolPolicy.executionDenialReason("longrun_task_update", session);
        assertNotNull(reason);
        assertTrue(reason.contains("worker session"));
    }

    @Test
    void taskUpdateDeniedInCommonMode() {
        ConversationSession session = new ConversationSession();

        assertNotNull(LongRunningToolPolicy.executionDenialReason("longrun_task_update", session));
    }

    @Test
    void taskUpdateDeniedInExecutingForControlSession() {
        ConversationSession executing = new ConversationSession();
        executing.setWorkflowMode(SessionMode.LONG_RUNNING);
        executing.setLongRunningStage(LongRunningStage.RUNNING);
        assertNotNull(LongRunningToolPolicy.executionDenialReason("longrun_task_update", executing));
    }

    // ---- Filter tests ----

    @Test
    void filterRemovesLongRunningToolsInCommonMode() {
        ConversationSession session = new ConversationSession();
        List<Tool<?>> tools = List.of(
                new StubTool("file_read", true),
                new StubTool("longrun_task_update", true));

        var filtered = LongRunningToolPolicy.filterVisibleTools(tools, session);
        assertEquals(1, filtered.size());
        assertEquals("file_read", filtered.iterator().next().name());
    }

    // ---- Worker session visibility tests ----

    @Test
    void workerSessionExecutingShowsTaskUpdateAndWorkerReport() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);
        session.setLongRunningWorkerSession(true);

        assertTrue(LongRunningToolPolicy.isToolVisible("longrun_task_update", session));
        assertTrue(LongRunningToolPolicy.isToolVisible("worker_report", session));
        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_plan_update", session));
        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_state_transition_request", session));
    }

    @Test
    void controlSessionExecutingHidesWorkerReportAndTaskUpdate() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);
        // NOT a worker session

        assertFalse(LongRunningToolPolicy.isToolVisible("worker_report", session));
        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_task_update", session));
        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_plan_update", session));
        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_state_transition_request", session));
    }

    @Test
    void interruptControlSessionShowsPlanAndTransitionTools() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.INTERRUPT);

        assertTrue(LongRunningToolPolicy.isToolVisible("longrun_plan_update", session));
        assertTrue(LongRunningToolPolicy.isToolVisible("longrun_state_transition_request", session));
        assertFalse(LongRunningToolPolicy.isToolVisible("worker_report", session));
        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_task_update", session));
    }

    @Test
    void workerSessionDoneHidesAllLongRunningTools() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DONE);
        session.setLongRunningWorkerSession(true);

        assertFalse(LongRunningToolPolicy.isToolVisible("worker_report", session));
        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_task_update", session));
        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_plan_update", session));
        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_state_transition_request", session));
    }

    @Test
    void workerSessionNonExecutingHidesAllLongRunningTools() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DRAFT);
        session.setLongRunningWorkerSession(true);

        assertFalse(LongRunningToolPolicy.isToolVisible("worker_report", session));
        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_task_update", session));
        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_plan_update", session));
        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_state_transition_request", session));
    }

    @Test
    void workerReportDeniedInControlSession() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);

        String reason = LongRunningToolPolicy.executionDenialReason("worker_report", session);
        assertNotNull(reason);
        assertTrue(reason.contains("worker session"));
    }

    private record StubTool(String name, boolean readOnly) implements Tool<ObjectNode> {
        @Override
        public Class<ObjectNode> inputType() { return ObjectNode.class; }
        @Override
        public String description() { return "stub"; }
        @Override
        public boolean isReadOnly() { return readOnly; }
        @Override
        public ObjectNode inputSchema(ObjectMapper mapper) {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", mapper.createObjectNode());
            return schema;
        }
        @Override
        public ToolResult execute(ObjectNode input, ToolUseContext context) {
            return new ToolResult(name(), true, "ok");
        }
    }
}
