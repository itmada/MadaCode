package madacode.tool.access;

import madacode.core.session.ConversationSession;
import madacode.tool.Tool;

/**
 * Workflow-specific overlay consulted by {@link ToolAccessResolver}. It contributes
 * the lifecycle gating decisions that depend on a session's workflow stage,
 * keeping that logic out of the generic resolver.
 *
 * <p>The resolver depends only on this interface; concrete workflows (e.g.
 * long-running) implement it and are wired in at the composition point. Additional
 * workflows plug in by providing another implementation — no change to the resolver
 * or its callers.
 *
 * <p>Session capability itself comes from
 * {@link madacode.core.session.ConversationSession#capabilityProfile()}.
 * This interface only handles stage-specific lifecycle tool gating.
 */
public interface WorkflowCapabilityPolicy {

    /**
     * Stage-gated vote for workflow lifecycle tools. Returns {@link WorkflowVote#expose()}
     * to surface the tool directly at the current stage, {@link WorkflowVote#deny}
     * with a reason when it is not permitted, or {@link WorkflowVote#abstain()} for
     * tools this policy does not govern.
     */
    WorkflowVote lifecycleVote(Tool<?> tool, ConversationSession session);

    /** A policy that imposes no role restriction and abstains on every tool. */
    static WorkflowCapabilityPolicy none() {
        return new WorkflowCapabilityPolicy() {
            @Override
            public WorkflowVote lifecycleVote(Tool<?> tool, ConversationSession session) {
                return WorkflowVote.abstain();
            }
        };
    }
}
