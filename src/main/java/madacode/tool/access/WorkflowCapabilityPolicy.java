package madacode.tool.access;

import madacode.core.session.ConversationSession;
import madacode.tool.Tool;

/**
 * Workflow-specific overlay consulted by {@link ToolAccessResolver}. It contributes
 * the two pieces of tool access that depend on a session's workflow role and stage,
 * keeping that logic out of the generic resolver.
 *
 * <p>The resolver depends only on this interface; concrete workflows (e.g.
 * long-running) implement it and are wired in at the composition point. Additional
 * workflows plug in by providing another implementation — no change to the resolver
 * or its callers.
 *
 * <p>The two methods are independent dimensions of the same workflow overlay.
 * A workflow that only narrows role capability can return {@link WorkflowVote#abstain()}
 * from {@link #lifecycleVote}; a workflow that only gates lifecycle tools can return
 * {@code null} from {@link #sessionProfile}.
 */
public interface WorkflowCapabilityPolicy {

    /**
     * Capability profile derived from the session's workflow role, or {@code null}
     * when the workflow imposes no role-specific restriction (the resolver then
     * falls back to {@link ToolCapabilityProfile#unrestricted()}).
     */
    ToolCapabilityProfile sessionProfile(ConversationSession session);

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
            public ToolCapabilityProfile sessionProfile(ConversationSession session) {
                return null;
            }

            @Override
            public WorkflowVote lifecycleVote(Tool<?> tool, ConversationSession session) {
                return WorkflowVote.abstain();
            }
        };
    }
}
