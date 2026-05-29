package madacode.core;

public record TurnResult(
        String finalText,
        FinishReason finishReason,
        int iterations) {

    public boolean completed() {
        return finishReason == FinishReason.COMPLETED;
    }
}
