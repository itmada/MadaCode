package madacode.eval;

/** Optional dimensional assertions attached to an eval case. */
public record EvalChecks(
        TrajectoryChecks trajectory,
        EfficiencyChecks efficiency,
        DialogChecks dialog,
        SafetyChecks safety) {

    public static final EvalChecks NONE = new EvalChecks(null, null, null, null);

    public boolean isEmpty() {
        return trajectory == null && efficiency == null && dialog == null && safety == null;
    }
}
