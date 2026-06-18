package madacode.eval;

import madacode.core.model.TokenUsage;
import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.session.ConversationSession;

/**
 * Aggregate metrics across every control, sub-agent, and worker session in one case.
 *
 * <p>Control- and worker-loop model iterations are tracked separately so reports can
 * distinguish planning/interactive effort ({@code controlIterations}) from autonomous
 * worker effort ({@code workerIterations}); merging them once hid which phase did the work.
 */
public record RunMetrics(
        int controlIterations,
        int workerIterations,
        int workerCycles,
        int toolCalls,
        TokenUsage tokenUsage) {

    public static final RunMetrics ZERO = new RunMetrics(0, 0, 0, 0, TokenUsage.ZERO);

    public RunMetrics {
        tokenUsage = tokenUsage == null ? TokenUsage.ZERO : tokenUsage;
    }

    public RunMetrics plus(RunMetrics other) {
        if (other == null) {
            return this;
        }
        return new RunMetrics(
                controlIterations + other.controlIterations,
                workerIterations + other.workerIterations,
                workerCycles + other.workerCycles,
                toolCalls + other.toolCalls,
                tokenUsage.plus(other.tokenUsage));
    }

    /** Total model iterations across control and worker loops (for at-a-glance reporting). */
    public int totalIterations() {
        return controlIterations + workerIterations;
    }

    /**
     * Builds metrics from a single control/interactive session: {@code controlIterations}
     * are the turn's iterations, worker counters are zero, and tool calls are counted from
     * the session transcript.
     */
    public static RunMetrics fromSession(ConversationSession session, int controlIterations) {
        int toolCalls = 0;
        for (Message message : session.messages()) {
            for (ContentBlock block : message.contentBlocks()) {
                if (block instanceof ContentBlock.ToolUseBlock) {
                    toolCalls++;
                }
            }
        }
        return new RunMetrics(controlIterations, 0, 0, toolCalls, session.tokenUsage());
    }
}
