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
    void stageUpdateVisibleInPlanning() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.PLANNING);

        assertTrue(LongRunningToolPolicy.isToolVisible("longrun_stage_update", session));
        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_task_update", session));
    }

    @Test
    void stageUpdateVisibleInWaitingForApproval() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.WAITING_FOR_APPROVAL);

        assertTrue(LongRunningToolPolicy.isToolVisible("longrun_stage_update", session));
        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_task_update", session));
    }

    @Test
    void taskUpdateVisibleInExecuting() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.EXECUTING);

        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_stage_update", session));
        assertTrue(LongRunningToolPolicy.isToolVisible("longrun_task_update", session));
    }

    @Test
    void neitherVisibleInCommonMode() {
        ConversationSession session = new ConversationSession();
        // Default is COMMON mode

        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_stage_update", session));
        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_task_update", session));
    }

    @Test
    void neitherVisibleInCompletedStage() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.COMPLETED);

        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_stage_update", session));
        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_task_update", session));
    }

    @Test
    void neitherVisibleInCancelledStage() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.CANCELLED);

        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_stage_update", session));
        assertFalse(LongRunningToolPolicy.isToolVisible("longrun_task_update", session));
    }

    @Test
    void nonLongRunningToolsAlwaysVisible() {
        ConversationSession session = new ConversationSession();
        assertTrue(LongRunningToolPolicy.isToolVisible("file_read", session));
        assertTrue(LongRunningToolPolicy.isToolVisible("bash", session));

        ConversationSession lrSession = new ConversationSession();
        lrSession.setWorkflowMode(SessionMode.LONG_RUNNING);
        lrSession.setLongRunningStage(LongRunningStage.EXECUTING);
        assertTrue(LongRunningToolPolicy.isToolVisible("file_read", lrSession));
    }

    // ---- Execution denial tests ----

    @Test
    void taskUpdateDeniedInPlanningStage() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.PLANNING);

        String reason = LongRunningToolPolicy.executionDenialReason("longrun_task_update", session);
        assertNotNull(reason);
        assertTrue(reason.contains("EXECUTING"));
    }

    @Test
    void taskUpdateDeniedInWaitingForApprovalStage() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.WAITING_FOR_APPROVAL);

        String reason = LongRunningToolPolicy.executionDenialReason("longrun_task_update", session);
        assertNotNull(reason);
        assertTrue(reason.contains("EXECUTING"));
    }

    @Test
    void stageUpdateDeniedInExecutingStage() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.EXECUTING);

        String reason = LongRunningToolPolicy.executionDenialReason("longrun_stage_update", session);
        assertNotNull(reason);
        assertTrue(reason.contains("PLANNING") || reason.contains("WAITING_FOR_APPROVAL"));
    }

    @Test
    void bothDeniedInCommonMode() {
        ConversationSession session = new ConversationSession();

        assertNotNull(LongRunningToolPolicy.executionDenialReason("longrun_task_update", session));
        assertNotNull(LongRunningToolPolicy.executionDenialReason("longrun_stage_update", session));
    }

    @Test
    void bothAllowedInCorrectStages() {
        ConversationSession planning = new ConversationSession();
        planning.setWorkflowMode(SessionMode.LONG_RUNNING);
        planning.setLongRunningStage(LongRunningStage.PLANNING);
        assertNull(LongRunningToolPolicy.executionDenialReason("longrun_stage_update", planning));

        ConversationSession executing = new ConversationSession();
        executing.setWorkflowMode(SessionMode.LONG_RUNNING);
        executing.setLongRunningStage(LongRunningStage.EXECUTING);
        assertNull(LongRunningToolPolicy.executionDenialReason("longrun_task_update", executing));
    }

    // ---- Filter tests ----

    @Test
    void filterRemovesLongRunningToolsInCommonMode() {
        ConversationSession session = new ConversationSession();
        List<Tool<?>> tools = List.of(
                new StubTool("file_read"),
                new StubTool("longrun_stage_update"),
                new StubTool("longrun_task_update"));

        var filtered = LongRunningToolPolicy.filterVisibleTools(tools, session);
        assertEquals(1, filtered.size());
        assertEquals("file_read", filtered.iterator().next().name());
    }

    @Test
    void filterKeepsStageUpdateInPlanning() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.PLANNING);
        List<Tool<?>> tools = List.of(
                new StubTool("file_read"),
                new StubTool("longrun_stage_update"),
                new StubTool("longrun_task_update"));

        var filtered = LongRunningToolPolicy.filterVisibleTools(tools, session);
        assertEquals(2, filtered.size());
        var names = filtered.stream().map(Tool::name).toList();
        assertTrue(names.contains("file_read"));
        assertTrue(names.contains("longrun_stage_update"));
        assertFalse(names.contains("longrun_task_update"));
    }

    private record StubTool(String name) implements Tool<ObjectNode> {
        @Override
        public Class<ObjectNode> inputType() { return ObjectNode.class; }
        @Override
        public String description() { return "stub"; }
        @Override
        public boolean isReadOnly() { return true; }
        @Override
        public ObjectNode inputSchema(ObjectMapper mapper) {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            return schema;
        }
        @Override
        public ToolResult execute(ObjectNode input, ToolUseContext context) {
            return new ToolResult(name(), true, "ok");
        }
    }
}