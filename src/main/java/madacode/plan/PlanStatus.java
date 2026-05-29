package madacode.plan;

public enum PlanStatus {
    PENDING, IN_PROGRESS, COMPLETED;

    public boolean isTerminal() {
        return this == COMPLETED;
    }

    public boolean canTransitionTo(PlanStatus target) {
        return target != null;
    }
}
