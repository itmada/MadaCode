package madacode.tool.access;

/**
 * A {@link WorkflowCapabilityPolicy} vote for one lifecycle tool.
 *
 * <ul>
 *   <li>{@code EXPOSE} — surface the tool directly at the current stage.</li>
 *   <li>{@code DENY} — the tool is not permitted now; {@link #reason()} explains why.</li>
 *   <li>{@code ABSTAIN} — this policy does not govern the tool; defer to normal rules.</li>
 * </ul>
 */
public record WorkflowVote(Kind kind, String reason) {

    public enum Kind { EXPOSE, DENY, ABSTAIN }

    private static final WorkflowVote EXPOSE = new WorkflowVote(Kind.EXPOSE, null);
    private static final WorkflowVote ABSTAIN = new WorkflowVote(Kind.ABSTAIN, null);

    public static WorkflowVote expose() {
        return EXPOSE;
    }

    public static WorkflowVote abstain() {
        return ABSTAIN;
    }

    public static WorkflowVote deny(String reason) {
        return new WorkflowVote(Kind.DENY, reason);
    }
}
