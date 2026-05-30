package madacode.core.model;

import madacode.services.compact.CompactResult;

/**
 * Ephemeral meta-events emitted alongside the message stream.
 * These are never persisted directly — they exist to drive live UI
 * indicators (spinners, status bar, dim status lines).
 */
public sealed interface MetaEvent permits
        MetaEvent.CompactStarted,
        MetaEvent.CompactCompleted,
        MetaEvent.CompactFailed,
        MetaEvent.PlanModeEntered,
        MetaEvent.PlanModeExited,
        MetaEvent.PlanRejected,
        MetaEvent.ModelRequestStarted,
        MetaEvent.TokenReport,
        MetaEvent.Error,
        MetaEvent.SubAgentStarted {

    record CompactStarted(int estimatedTokens, int softLimit) implements MetaEvent {}
    record CompactCompleted(CompactResult result) implements MetaEvent {}
    record CompactFailed(String reason) implements MetaEvent {}
    record PlanModeEntered() implements MetaEvent {}
    record PlanModeExited() implements MetaEvent {}
    record PlanRejected(String summary) implements MetaEvent {}
    record ModelRequestStarted() implements MetaEvent {}
    record TokenReport(TokenUsage usage, long ttftMs, long totalMs) implements MetaEvent {}
    record Error(String message, FinishReason reason) implements MetaEvent {}

    /**
     * Fired by a tool (AgentTool, SkillTool) on the parent session immediately
     * before a sub-agent is launched. Drives the live progress indicator so the
     * user can see which agent is running and why.
     *
     * @param label     short task description supplied by the caller (3-5 words)
     * @param agentType agent type string, e.g. "explorer", "planner", "skill"
     */
    record SubAgentStarted(String label, String agentType) implements MetaEvent {}
}
