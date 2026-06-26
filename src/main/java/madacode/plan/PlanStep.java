package madacode.plan;

public record PlanStep(String step, PlanStepStatus status) {

    public PlanStep {
        step = step == null ? "" : step.strip();
        status = status == null ? PlanStepStatus.PENDING : status;
        if (step.isBlank()) {
            throw new IllegalArgumentException("step must not be blank");
        }
    }
}
