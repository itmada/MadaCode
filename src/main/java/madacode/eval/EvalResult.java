package madacode.eval;

import java.util.List;

public record EvalResult(
        String scenario,
        boolean passed,
        long durationMs,
        int iterations,
        int toolCalls,
        List<String> failures) {

    public static EvalResult pass(String scenario, long durationMs, int iterations, int toolCalls) {
        return new EvalResult(scenario, true, durationMs, iterations, toolCalls, List.of());
    }

    public static EvalResult fail(String scenario, long durationMs, int iterations, int toolCalls, List<String> failures) {
        return new EvalResult(scenario, false, durationMs, iterations, toolCalls, failures);
    }
}
