package madacode.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.core.model.ToolResult;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.tool.Tool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

public class SystemPromptBuilderLongRunningTest {

    @Test
    void longRunningPlanningPromptIsInjectedOnlyWhenStageIsActive() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.PLANNING);

        String prompt = new SystemPromptBuilder().build(
                List.of(new StubTool("file_read"), new StubTool("longrun_stage_update")),
                session.workingDirectory(),
                session);

        assertTrue(prompt.contains("## Long-Running Workflow"));
        assertTrue(prompt.contains("Long-running stage: PLANNING."));
        assertTrue(prompt.contains("intent=FINALIZE_PLAN"));
        assertTrue(prompt.contains("Do not make code changes yet."));
    }

    @Test
    void longRunningApprovalPromptMentionsApproveExecution() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.WAITING_FOR_APPROVAL);

        String prompt = new SystemPromptBuilder().build(
                List.of(new StubTool("longrun_stage_update")),
                session.workingDirectory(),
                session);

        assertTrue(prompt.contains("Long-running stage: WAITING_FOR_APPROVAL."));
        assertTrue(prompt.contains("intent=APPROVE_EXECUTION"));
    }

    @Test
    void longRunningExecutingPromptEnforcesIssueFirstSingleItemLoop() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.EXECUTING);

        String prompt = new SystemPromptBuilder().build(
                List.of(new StubTool("file_read"), new StubTool("longrun_task_update")),
                session.workingDirectory(),
                session);

        assertTrue(prompt.contains("Long-running stage: EXECUTING."));
        assertTrue(prompt.contains("known-issues.json"));
        assertTrue(prompt.contains("fix exactly one issue"));
        assertTrue(prompt.contains("do not pick a feature"));
        assertTrue(prompt.contains("pick exactly one eligible feature"));
        assertTrue(prompt.contains("progress.txt"));
        assertTrue(prompt.contains("longrun_task_update"));
        assertTrue(prompt.contains("passes value from false to true"));
    }

    @Test
    void longRunningToolVisibilityTracksStage() {
        ConversationSession planning = new ConversationSession();
        planning.setWorkflowMode(SessionMode.LONG_RUNNING);
        planning.setLongRunningStage(LongRunningStage.PLANNING);

        String planningPrompt = new SystemPromptBuilder().build(
                List.of(new StubTool("longrun_stage_update"), new StubTool("longrun_task_update")),
                planning.workingDirectory(),
                planning);
        assertTrue(planningPrompt.contains("Available tools: longrun_stage_update"));
        assertFalse(planningPrompt.contains("Available tools: longrun_stage_update, longrun_task_update"));

        ConversationSession executing = new ConversationSession();
        executing.setWorkflowMode(SessionMode.LONG_RUNNING);
        executing.setLongRunningStage(LongRunningStage.EXECUTING);

        String executingPrompt = new SystemPromptBuilder().build(
                List.of(new StubTool("longrun_stage_update"), new StubTool("longrun_task_update")),
                executing.workingDirectory(),
                executing);
        assertTrue(executingPrompt.contains("Available tools: longrun_task_update"));
        assertFalse(executingPrompt.contains("Available tools: longrun_stage_update, longrun_task_update"));
    }

    @Test
    void commonModePromptDoesNotMentionLongRunningWorkflow() {
        String prompt = new SystemPromptBuilder().build(
                List.of(new StubTool("file_read"), new StubTool("longrun_stage_update")));

        assertFalse(prompt.contains("## Long-Running Workflow"));
        assertFalse(prompt.contains("Available tools: file_read, longrun_stage_update"));
        assertFalse(prompt.contains("longrun_stage_update"));
        assertFalse(prompt.contains("intent=FINALIZE_PLAN"));
        assertFalse(prompt.contains("intent=APPROVE_EXECUTION"));
    }

    private record StubTool(String name) implements Tool<ObjectNode> {
        @Override
        public Class<ObjectNode> inputType() {
            return ObjectNode.class;
        }

        @Override
        public String description() {
            return "stub";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

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
