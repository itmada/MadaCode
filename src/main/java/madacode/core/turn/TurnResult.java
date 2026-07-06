package madacode.core.turn;

import madacode.core.model.FinishReason;
import madacode.services.api.ApiFailureClassification;

public record TurnResult(
        String finalText,
        FinishReason finishReason,
        int iterations,
        ApiFailureClassification apiFailure) {

    public TurnResult(String finalText, FinishReason finishReason, int iterations) {
        this(finalText, finishReason, iterations, null);
    }

    public boolean completed() {
        return finishReason == FinishReason.COMPLETED;
    }
}
