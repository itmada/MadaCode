package madacode.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.core.model.ToolResult;
import madacode.core.session.ConversationSession;
import madacode.core.session.ConversationSession.LongRunningStageUpdateIntent;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

class LongRunStageUpdateToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ConversationSession session;
    private ToolUseContext context;
    private LongRunStageUpdateTool tool;

    @BeforeEach
    void setUp() {
        session = new ConversationSession();
        context = new ToolUseContext(Path.of(System.getProperty("user.dir")), session);
        tool = new LongRunStageUpdateTool();
    }

    @Test
    void failsOutsideLongRunningMode() {
        ToolResult result = ToolTestSupport.invoke(tool,
                input("FINALIZE_PLAN", "high", "User finished discussing the plan."),
                context);

        assertFalse(result.success());
        assertTrue(result.output().contains("Long-running mode is not active"));
    }

    @Test
    void recordsHighConfidencePlanningSuggestion() {
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.PLANNING);

        ToolResult result = ToolTestSupport.invoke(tool,
                input("FINALIZE_PLAN", "high", "The user said the plan looks good."),
                context);

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("ready_for_transition: true"));
        var update = session.lastLongRunningStageUpdate().orElseThrow();
        assertEquals(LongRunningStage.PLANNING, update.stage());
        assertEquals(LongRunningStageUpdateIntent.FINALIZE_PLAN, update.intent());
        assertEquals(ConversationSession.LongRunningConfidence.HIGH, update.confidence());
        assertEquals("The user said the plan looks good.", update.summary());
    }

    @Test
    void stageUpdateToolIsInternalAndDoesNotRequireUserPermission() {
        assertTrue(tool.isReadOnly());
        assertFalse(tool.isConcurrencySafe(new LongRunStageUpdateTool.Input(
                "FINALIZE_PLAN", "high", "done")));
    }

    @Test
    void recordsLowConfidenceWithoutTransitionReadiness() {
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.WAITING_FOR_APPROVAL);

        ToolResult result = ToolTestSupport.invoke(tool,
                input("APPROVE_EXECUTION", "low", "The user might be leaning yes but is not explicit."),
                context);

        assertTrue(result.success(), result.output());
        assertTrue(result.output().contains("ready_for_transition: false"));
        assertEquals(ConversationSession.LongRunningConfidence.LOW,
                session.lastLongRunningStageUpdate().orElseThrow().confidence());
    }

    @Test
    void rejectsIntentNotAllowedForStage() {
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.PLANNING);

        ToolResult result = ToolTestSupport.invoke(tool,
                input("APPROVE_EXECUTION", "high", "Start coding now."),
                context);

        assertFalse(result.success());
        assertTrue(result.output().contains("is not allowed"));
    }

    @Test
    void rejectsBlankSummary() {
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.PLANNING);

        ToolResult result = ToolTestSupport.invoke(tool,
                input("FINALIZE_PLAN", "high", "   "),
                context);

        assertFalse(result.success());
        assertTrue(result.output().contains("summary must be non-empty"));
    }

    @Test
    void planningStageRejectsCancelIntent() {
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.PLANNING);

        ToolResult result = ToolTestSupport.invoke(tool,
                input("CANCEL", "high", "Stop the workflow."),
                context);

        assertFalse(result.success());
        assertTrue(result.output().contains("is not allowed"));
    }

    @Test
    void executingStageRejectsToolUse() {
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.EXECUTING);

        ToolResult result = ToolTestSupport.invoke(tool,
                input("COMPLETE", "high", "Execution appears done."),
                context);

        assertFalse(result.success());
        assertTrue(result.output().contains("is not allowed"));
    }

    @Test
    void changingStageClearsPreviousSuggestion() {
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.PLANNING);
        ToolTestSupport.invoke(tool,
                input("FINALIZE_PLAN", "high", "Plan is complete."),
                context);

        session.setLongRunningStage(LongRunningStage.WAITING_FOR_APPROVAL);

        assertTrue(session.lastLongRunningStageUpdate().isEmpty());
    }

    private ObjectNode input(String intent, String confidence, String summary) {
        ObjectNode input = mapper.createObjectNode();
        input.put("intent", intent);
        input.put("confidence", confidence);
        input.put("summary", summary);
        return input;
    }
}
