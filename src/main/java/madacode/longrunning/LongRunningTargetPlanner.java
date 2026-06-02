package madacode.longrunning;

import madacode.core.session.LongRunningTurnAssignment;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Selects the single work target for the next EXECUTING turn.
 *
 * <p>The model should not decide whether to work on issues, seed the feature
 * list, or choose a feature opportunistically. That decision is derived from
 * task-store state by the harness and injected into the prompt.
 */
public final class LongRunningTargetPlanner {

    private final LongRunningTaskStore store;

    public LongRunningTargetPlanner(LongRunningTaskStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public LongRunningTurnAssignment assign(String taskId) {
        Objects.requireNonNull(taskId, "taskId");

        List<KnownIssue> activeIssues = store.readKnownIssues(taskId).stream()
                .filter(issue -> "open".equals(issue.status()) || "blocked".equals(issue.status()))
                .sorted(Comparator
                        .comparingInt((KnownIssue issue) -> severityRank(issue.severity()))
                        .thenComparing(KnownIssue::createdAt))
                .toList();
        if (!activeIssues.isEmpty()) {
            KnownIssue issue = activeIssues.getFirst();
            return new LongRunningTurnAssignment(
                    LongRunningTurnAssignment.Kind.ISSUE,
                    issue.id(),
                    issue.description(),
                    "Active known issues take priority over feature work.",
                    issue.verificationSteps());
        }

        List<FeatureItem> features = store.readFeatureList(taskId);
        if (features.isEmpty()) {
            return new LongRunningTurnAssignment(
                    LongRunningTurnAssignment.Kind.SEED_FEATURE_LIST,
                    null,
                    "Create the initial feature list from the approved plan.",
                    "feature_list.json is empty.",
                    List.of());
        }

        Set<String> passed = new HashSet<>();
        for (FeatureItem feature : features) {
            if (feature.passes()) {
                passed.add(feature.id());
            }
        }

        for (FeatureItem feature : features) {
            if (feature.passes()) {
                continue;
            }
            if (passed.containsAll(feature.dependsOn())) {
                return new LongRunningTurnAssignment(
                        LongRunningTurnAssignment.Kind.FEATURE,
                        feature.id(),
                        feature.description(),
                        feature.dependsOn().isEmpty()
                                ? "First incomplete feature with no unmet dependencies."
                                : "First incomplete feature whose dependencies have passed.",
                        feature.verificationSteps());
            }
        }

        boolean allPassed = features.stream().allMatch(FeatureItem::passes);
        if (allPassed) {
            return new LongRunningTurnAssignment(
                    LongRunningTurnAssignment.Kind.COMPLETE_TASK,
                    null,
                    "Mark the long-running task complete.",
                    "All features have passed and no active issues remain.",
                    List.of());
        }

        return new LongRunningTurnAssignment(
                LongRunningTurnAssignment.Kind.BLOCKED,
                null,
                "No eligible feature can be selected.",
                "Incomplete features exist, but each has unmet dependencies.",
                List.of());
    }

    private static int severityRank(String severity) {
        if (severity == null) {
            return 3;
        }
        return switch (severity.strip().toLowerCase()) {
            case "critical" -> 0;
            case "high" -> 1;
            case "medium" -> 2;
            case "low" -> 3;
            default -> 4;
        };
    }
}
