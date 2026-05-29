package madacode.services.compact;

public record CompactBudget(int totalTokens, double softRatio, int keepRecentRounds, int microMaxResultChars) {

    public static CompactBudget defaults() {
        return new CompactBudget(200_000, 0.85, 3, 4_000);
    }

    public int softLimit() {
        return (int) (totalTokens * softRatio);
    }

    public boolean isOverSoft(int estimatedTokens) {
        return estimatedTokens > softLimit();
    }
}
