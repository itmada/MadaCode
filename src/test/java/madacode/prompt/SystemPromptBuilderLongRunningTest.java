package madacode.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.core.model.ToolResult;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.LongRunningTurnAssignment;
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
        assertTrue(prompt.contains("Do not make code changes, create files, run implementation commands"));
        assertTrue(prompt.contains("Do not use normal plan_create, plan_update, or todo_write"));
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
        assertTrue(prompt.contains("intent=REVISE_PLAN"));
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
        assertTrue(prompt.contains("[HARNESS WARNING]"));
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

    @Test
    void planningStageHidesWriteToolsFromPrompt() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.PLANNING);

        String prompt = new SystemPromptBuilder().build(
                List.of(
                        new StubTool("file_read"),
                        new StubTool("bash", false),
                        new StubTool("write", false),
                        new StubTool("edit", false),
                        new StubTool("plan_create", false),
                        new StubTool("plan_get"),
                        new StubTool("plan_list"),
                        new StubTool("enter_plan_mode"),
                        new StubTool("exit_plan_mode"),
                        new StubTool("longrun_stage_update")),
                session.workingDirectory(),
                session);

        String toolsSection = extractToolsSection(prompt);
        assertTrue(toolsSection.contains("file_read"));
        assertFalse(toolsSection.contains("plan_create"));
        assertFalse(toolsSection.contains("plan_get"));
        assertFalse(toolsSection.contains("plan_list"));
        assertFalse(toolsSection.contains("enter_plan_mode"));
        assertFalse(toolsSection.contains("exit_plan_mode"));
        assertTrue(toolsSection.contains("longrun_stage_update"));
        assertFalse(toolsSection.contains("bash"));
        assertFalse(toolsSection.contains("write"));
        assertFalse(toolsSection.contains("edit"));
        assertTrue(prompt.contains("Do not use normal plan_create, plan_update, or todo_write"));
        assertTrue(prompt.contains("Do not choose product scope"));
    }

    @Test
    void executingPromptShowsTaskContextWhenAvailable() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.EXECUTING);
        session.setLongRunningTaskId("task-20260601-120000");
        session.setLongRunningTaskDirectory("/tmp/.mada/long-running/task-20260601-120000");
        session.setLongRunningTurnAssignment(new LongRunningTurnAssignment(
                LongRunningTurnAssignment.Kind.FEATURE,
                "feature-api",
                "Implement the API",
                "First eligible feature.",
                List.of("Run API tests")));

        String prompt = new SystemPromptBuilder().build(
                List.of(new StubTool("file_read"), new StubTool("longrun_task_update")),
                session.workingDirectory(),
                session);

        assertTrue(prompt.contains("Active task id: task-20260601-120000"));
        assertTrue(prompt.contains("Task store directory: /tmp/.mada/long-running/task-20260601-120000"));
        assertTrue(prompt.contains("Read known-issues.json and feature_list.json from this directory."));
        assertTrue(prompt.contains("Assigned target kind: FEATURE."));
        assertTrue(prompt.contains("Assigned target id: feature-api."));
        assertTrue(prompt.contains("Only work on the assigned target for this execution turn."));
        assertTrue(prompt.contains("Assigned verification steps: Run API tests"));
        assertFalse(prompt.contains("[HARNESS WARNING]"));
    }

    private static String extractToolsSection(String prompt) {
        int start = prompt.indexOf("## Tools");
        int end = prompt.indexOf("## ", start + 8);
        if (start < 0) return "";
        return end < 0 ? prompt.substring(start) : prompt.substring(start, end);
    }

    private record StubTool(String name, boolean readOnly) implements Tool<ObjectNode> {
        StubTool(String name) {
            this(name, true);
        }

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
            return readOnly;
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
