package madacode.eval;

public record EvalAssertion(String type, String expected) {

    public static EvalAssertion toolCalled(String name) {
        return new EvalAssertion("tool_called", name);
    }

    public static EvalAssertion outputContains(String text) {
        return new EvalAssertion("output_contains", text);
    }

    public static EvalAssertion finishReason(String reason) {
        return new EvalAssertion("finish_reason", reason);
    }

    public static EvalAssertion iterationCount(String count) {
        return new EvalAssertion("iteration_count", count);
    }
}
