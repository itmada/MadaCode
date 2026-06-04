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
    void longRunningDraftPromptInjectedOnlyWhenStageIsActive() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DRAFT);

        String prompt = new SystemPromptBuilder().build(
                List.of(new StubTool("file_read")),
                session.workingDirectory(),
                session);

        assertTrue(prompt.contains("## Long-Running Workflow"));
        assertTrue(prompt.contains("Current stage: DRAFT."));
        assertTrue(prompt.contains("The current stage shown here is the only source of truth"));
        assertTrue(prompt.contains("State transitions are requested by the model"));
        assertTrue(prompt.contains("Forbidden"));
        assertTrue(prompt.contains("discuss goals"));
        assertTrue(prompt.contains("request a transition to RUNNING"));
    }

    @Test
    void longRunningRunningPromptMentionsControlSessionConstraints() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);

        String prompt = new SystemPromptBuilder().build(
                List.of(new StubTool("file_read")),
                session.workingDirectory(),
                session);

        assertTrue(prompt.contains("Current stage: RUNNING."));
        assertTrue(prompt.contains("does not implement project files directly"));
        assertTrue(prompt.contains("Launcher/worker execution is managed mechanically"));
        assertTrue(prompt.contains("request a state transition"));
        assertTrue(prompt.contains("Forbidden"));
    }

    @Test
    void longRunningDonePromptIsSummaryOnly() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DONE);

        String prompt = new SystemPromptBuilder().build(
                List.of(new StubTool("file_read")),
                session.workingDirectory(),
                session);

        assertTrue(prompt.contains("Current stage: DONE."));
        assertTrue(prompt.contains("terminal control state"));
        assertTrue(prompt.contains("Allowed: summarize"));
        assertTrue(prompt.contains("Forbidden"));
        assertFalse(prompt.contains("Launcher/worker execution is managed mechanically"));
    }

    @Test
    void longRunningToolVisibilityTracksStage() {
        ConversationSession planning = new ConversationSession();
        planning.setWorkflowMode(SessionMode.LONG_RUNNING);
        planning.setLongRunningStage(LongRunningStage.DRAFT);

        String planningPrompt = new SystemPromptBuilder().build(
                List.of(new StubTool("file_read"), new StubTool("longrun_task_update")),
                planning.workingDirectory(),
                planning);
        assertTrue(planningPrompt.contains("Available tools: file_read"));
        assertFalse(planningPrompt.contains("longrun_task_update"));

        ConversationSession executing = new ConversationSession();
        executing.setWorkflowMode(SessionMode.LONG_RUNNING);
        executing.setLongRunningStage(LongRunningStage.RUNNING);

        String executingPrompt = new SystemPromptBuilder().build(
                List.of(new StubTool("file_read"), new StubTool("longrun_task_update")),
                executing.workingDirectory(),
                executing);
        assertFalse(extractToolsSection(executingPrompt).contains("longrun_task_update"));
        assertTrue(extractToolsSection(executingPrompt).contains("file_read"));
    }

    @Test
    void commonModePromptDoesNotMentionLongRunningWorkflow() {
        String prompt = new SystemPromptBuilder().build(
                List.of(new StubTool("file_read")));

        assertFalse(prompt.contains("## Long-Running Workflow"));
    }

    @Test
    void draftStageHidesWriteToolsFromPrompt() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DRAFT);

        String prompt = new SystemPromptBuilder().build(
                List.of(
                        new StubTool("file_read"),
                        new StubTool("ask_user_question"),
                        new StubTool("bash", false),
                        new StubTool("write", false),
                        new StubTool("edit", false),
                        new StubTool("plan_create", false)),
                session.workingDirectory(),
                session);

        String toolsSection = extractToolsSection(prompt);
        assertTrue(toolsSection.contains("file_read"));
        assertTrue(toolsSection.contains("ask_user_question"));
        assertFalse(toolsSection.contains("plan_create"));
        assertFalse(toolsSection.contains("bash"));
        assertFalse(toolsSection.contains("write"));
        assertFalse(toolsSection.contains("edit"));
        assertTrue(prompt.contains("Forbidden"));
        assertTrue(prompt.contains("do not create/edit/delete project files"));
        assertTrue(prompt.contains("Current capability: discuss goals"));
    }

    @Test
    void runningPromptShowsTaskContext() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);
        session.setLongRunningTaskId("task-20260601-120000");
        session.setLongRunningTaskDirectory("/tmp/.mada/long-running/task-20260601-120000");

        String prompt = new SystemPromptBuilder().build(
                List.of(new StubTool("file_read")),
                session.workingDirectory(),
                session);

        assertTrue(prompt.contains("Active task id: task-20260601-120000"));
        assertTrue(prompt.contains("Task store directory: /tmp/.mada/long-running/task-20260601-120000"));
        assertTrue(prompt.contains("This control session does not implement project files directly"));
        assertFalse(prompt.contains("WAITING_FOR_APPROVAL"));
        assertFalse(prompt.contains("/longrun-approve"));
    }

    @Test
    void draftStageShowsTaskShellContext() {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DRAFT);
        session.setLongRunningTaskId("task-1");
        session.setLongRunningTaskDirectory("/tmp/.mada/long-running/task-1");

        String prompt = new SystemPromptBuilder().build(
                List.of(new StubTool("file_read")),
                session.workingDirectory(),
                session);

        assertTrue(prompt.contains("Current stage: DRAFT."));
        assertTrue(prompt.contains("Draft task id: task-1"));
        assertTrue(prompt.contains("Task shell directory: /tmp/.mada/long-running/task-1"));
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
