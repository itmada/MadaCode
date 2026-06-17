package madacode.tool.access;

import madacode.core.session.ConversationSession;
import madacode.tool.Tool;

import java.util.List;
import java.util.Objects;

/**
 * Ordered composition for workflow capability overlays.
 *
 * <p>Profiles are combined by first non-null answer. Lifecycle votes are combined
 * by first non-abstaining vote. This keeps workflow-specific rules pluggable while
 * preserving deterministic precedence.
 */
public final class CompositeWorkflowCapabilityPolicy implements WorkflowCapabilityPolicy {

    private final List<WorkflowCapabilityPolicy> policies;

    private CompositeWorkflowCapabilityPolicy(List<WorkflowCapabilityPolicy> policies) {
        this.policies = List.copyOf(policies);
    }

    public static WorkflowCapabilityPolicy of(WorkflowCapabilityPolicy... policies) {
        if (policies == null || policies.length == 0) {
            return WorkflowCapabilityPolicy.none();
        }
        List<WorkflowCapabilityPolicy> safePolicies = java.util.Arrays.stream(policies)
                .filter(Objects::nonNull)
                .toList();
        if (safePolicies.isEmpty()) {
            return WorkflowCapabilityPolicy.none();
        }
        if (safePolicies.size() == 1) {
            return safePolicies.getFirst();
        }
        return new CompositeWorkflowCapabilityPolicy(safePolicies);
    }

    @Override
    public ToolCapabilityProfile sessionProfile(ConversationSession session) {
        for (WorkflowCapabilityPolicy policy : policies) {
            ToolCapabilityProfile profile = policy.sessionProfile(session);
            if (profile != null) {
                return profile;
            }
        }
        return null;
    }

    @Override
    public WorkflowVote lifecycleVote(Tool<?> tool, ConversationSession session) {
        for (WorkflowCapabilityPolicy policy : policies) {
            WorkflowVote vote = policy.lifecycleVote(tool, session);
            if (vote != null && vote.kind() != WorkflowVote.Kind.ABSTAIN) {
                return vote;
            }
        }
        return WorkflowVote.abstain();
    }
}
