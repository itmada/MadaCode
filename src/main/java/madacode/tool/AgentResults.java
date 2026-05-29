package madacode.tool;

import madacode.core.FinishReason;
import madacode.core.ToolResult;
import madacode.core.TurnResult;

/**
 * Package-private utilities shared by tools that launch sub-agents
 * (AgentTool, SkillTool). Centralises the TurnResult → ToolResult
 * translation so the two callers stay in sync.
 */
final class AgentResults {

    private AgentResults() {}

    /**
     * Converts a sub-agent {@link TurnResult} into the {@link ToolResult}
     * the parent agent will see. Only {@link FinishReason#COMPLETED} is
     * treated as success; every other reason (MAX_ITERATIONS, CANCELLED,
     * API_ERROR, …) becomes a failure so the parent can react.
     */
    static ToolResult toToolResult(String callerName, TurnResult result) {
        String body = result.finalText() == null ? "" : result.finalText();
        if (result.finishReason() == FinishReason.COMPLETED) {
            return new ToolResult(callerName, true, body);
        }
        return new ToolResult(callerName, false,
                "Sub-agent did not complete (" + result.finishReason() + "): " + body);
    }
}
