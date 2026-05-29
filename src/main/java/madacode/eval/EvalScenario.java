package madacode.eval;

import java.util.List;

public record EvalScenario(
        String description,
        String userInput,
        List<MockApiResponse> mockApiResponses,
        List<EvalAssertion> assertions) {
}
