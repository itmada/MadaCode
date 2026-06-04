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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SystemPromptBuilderLongRunningTest {

    @Test
    void draftPromptDescribesPlanUpdateAndTransitionRequest() {
        ConversationSession session = longRunning(LongRunningStage.DRAFT);

        String prompt = prompt(session, new StubTool("file_read"));

        assertTrue(prompt.contains("## Long-Running Workflow"));
        assertTrue(prompt.contains("Current stage: DRAFT."));
        assertTrue(prompt.contains("longrun_plan_update"));
        assertTrue(prompt.contains("longrun_state_transition_request"));
        assertTrue(prompt.contains("Top-level long-running stages are DRAFT, RUNNING, and DONE"));
        assertFalse(prompt.contains("WAITING_FOR_APPROVAL"));
        assertFalse(prompt.contains("approved_plan.md"));
    }

    @Test
    void runningPromptIsControlSessionOnly() {
        ConversationSession session = longRunning(LongRunningStage.RUNNING);

        String prompt = prompt(session, new StubTool("file_read"));

        assertTrue(prompt.contains("Current stage: RUNNING."));
        assertTrue(prompt.contains("This control session does not implement project files directly"));
        assertTrue(prompt.contains("fresh worker sessions"));
        assertTrue(prompt.contains("do not call longrun_task_update or worker_report"));
        assertFalse(prompt.contains("EXECUTING"));
    }

    @Test
    void donePromptIsTerminal() {
        ConversationSession session = longRunning(LongRunningStage.DONE);

        String prompt = prompt(session, new StubTool("file_read"));

        assertTrue(prompt.contains("Long-running stage: DONE."));
        assertTrue(prompt.contains("terminal"));
    }

    @Test
    void longRunningToolVisibilityTracksRoleAndStage() {
        ConversationSession draft = longRunning(LongRunningStage.DRAFT);
        String draftPrompt = prompt(draft,
                new StubTool("file_read"),
                new StubTool("longrun_plan_update", false),
                new StubTool("longrun_state_transition_request", false),
                new StubTool("longrun_task_update", false),
                new StubTool("worker_report", false));
        String draftTools = extractToolsSection(draftPrompt);
        assertTrue(draftTools.contains("file_read"));
        assertTrue(draftTools.contains("longrun_plan_update"));
        assertTrue(draftTools.contains("longrun_state_transition_request"));
        assertFalse(draftTools.contains("longrun_task_update"));
        assertFalse(draftTools.contains("worker_report"));

        ConversationSession runningControl = longRunning(LongRunningStage.RUNNING);
        String runningPrompt = prompt(runningControl,
                new StubTool("file_read"),
                new StubTool("longrun_plan_update", false),
                new StubTool("longrun_state_transition_request", false),
                new StubTool("longrun_task_update", false),
                new StubTool("worker_report", false));
        String runningTools = extractToolsSection(runningPrompt);
        assertTrue(runningTools.contains("file_read"));
        assertTrue(runningTools.contains("longrun_state_transition_request"));
        assertFalse(runningTools.contains("longrun_plan_update"));
        assertFalse(runningTools.contains("longrun_task_update"));
        assertFalse(runningTools.contains("worker_report"));
    }

    @Test
    void commonModePromptDoesNotMentionLongRunningWorkflow() {
        String prompt = new SystemPromptBuilder().build(List.of(new StubTool("file_read")));

        assertFalse(prompt.contains("## Long-Running Workflow"));
    }

    @Test
    void runningPromptShowsTaskContext() {
        ConversationSession session = longRunning(LongRunningStage.RUNNING);
        session.setLongRunningTaskId("task-20260601-120000");
        session.setLongRunningTaskDirectory("/tmp/.mada/long-running/task-20260601-120000");

        String prompt = prompt(session, new StubTool("file_read"));

        assertTrue(prompt.contains("Active task id: task-20260601-120000"));
        assertTrue(prompt.contains("Task store directory: /tmp/.mada/long-running/task-20260601-120000"));
        assertFalse(prompt.contains("Assigned target kind"));
        assertFalse(prompt.contains("handoff protocol"));
    }

    private static ConversationSession longRunning(LongRunningStage stage) {
        ConversationSession session = new ConversationSession();
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(stage);
        return session;
    }

    private static String prompt(ConversationSession session, Tool<?>... tools) {
        return new SystemPromptBuilder().build(List.of(tools), session.workingDirectory(), session);
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
            return schema;
        }

        @Override
        public ToolResult execute(ObjectNode input, ToolUseContext context) {
            return new ToolResult(name, true, "ok");
        }
    }
}
