package madacode.eval;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Ordered, fail-closed composition of independent dimensional scorers. */
public final class ScorerPipeline {

    private final List<Scorer> scorers;

    private ScorerPipeline(List<Scorer> scorers) {
        this.scorers = List.copyOf(scorers);
        Set<Dimension> dimensions = new LinkedHashSet<>();
        for (Scorer scorer : this.scorers) {
            Objects.requireNonNull(scorer, "scorer");
            if (!dimensions.add(scorer.dimension())) {
                throw new IllegalArgumentException(
                        "duplicate scorer for dimension " + scorer.dimension());
            }
        }
        if (!dimensions.contains(Dimension.VERIFY)) {
            throw new IllegalArgumentException("scorer pipeline requires a VERIFY scorer");
        }
    }

    public static ScorerPipeline of(Scorer... scorers) {
        return new ScorerPipeline(Arrays.asList(scorers));
    }

    public List<DimensionScore> run(EvalCase evalCase, ScoringContext context) {
        List<DimensionScore> scores = new ArrayList<>();
        for (Scorer scorer : scorers) {
            boolean applies;
            try {
                applies = scorer.appliesTo(evalCase);
            } catch (RuntimeException e) {
                scores.add(errorScore(scorer, evalCase, e));
                continue;
            }
            if (applies) {
                scores.add(scoreSafely(scorer, evalCase, context));
            }
        }
        return List.copyOf(scores);
    }

    public String reproducibilityFingerprint() {
        return scorers.stream()
                .map(Scorer::reproducibilityDescriptor)
                .collect(java.util.stream.Collectors.joining("|"));
    }

    private static DimensionScore scoreSafely(
            Scorer scorer,
            EvalCase evalCase,
            ScoringContext context) {
        try {
            boolean gating = scorer.gating(evalCase);
            DimensionScore score = Objects.requireNonNull(
                    scorer.score(evalCase, context),
                    () -> scorer.dimension() + " scorer returned null");
            if (score.dimension() != scorer.dimension()) {
                throw new IllegalStateException(
                        scorer.dimension() + " scorer returned " + score.dimension());
            }
            if (score.gating() != gating) {
                throw new IllegalStateException(
                        scorer.dimension() + " scorer returned inconsistent gating");
            }
            return score;
        } catch (RuntimeException e) {
            return errorScore(scorer, evalCase, e);
        }
    }

    private static DimensionScore errorScore(
            Scorer scorer,
            EvalCase evalCase,
            RuntimeException error) {
        boolean gating;
        try {
            gating = scorer.gating(evalCase);
        } catch (RuntimeException ignored) {
            gating = true;
        }
        return new DimensionScore(
                scorer.dimension(),
                EvalResult.JudgeStatus.ERROR,
                gating,
                "judge crashed: " + errorMessage(error));
    }

    private static String errorMessage(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
