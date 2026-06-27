package madacode.tool;

import madacode.core.session.ConversationSession;
import madacode.longrunning.FeatureItem;
import madacode.longrunning.KnownIssue;
import madacode.longrunning.LongRunningTaskInitializer;
import madacode.tool.schema.OptionalSchemaProperty;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class LongRunEnvironmentSupport {

    record FeatureInput(
            String id,
            String category,
            String priority,
            String description,
            @OptionalSchemaProperty
            List<String> depends_on,
            @OptionalSchemaProperty
            List<String> verification_steps,
            @OptionalSchemaProperty
            Boolean passes) {}

    record IssueInput(
            String id,
            String description,
            String severity,
            @OptionalSchemaProperty
            String status,
            @OptionalSchemaProperty
            String discovered_in,
            @OptionalSchemaProperty
            List<String> verification_steps) {}

    private LongRunEnvironmentSupport() {}

    static String activeTaskId(String requestedTaskId, ConversationSession session) {
        String active = session.longRunningTaskId();
        if (requestedTaskId == null || requestedTaskId.isBlank()) {
            return active;
        }
        String requested = requestedTaskId.strip();
        if (active == null || !requested.equals(active)) {
            throw new IllegalArgumentException("task_id does not match the active long-running task.");
        }
        return requested;
    }

    static String deriveTitle(String title, String summary, String currentTitle) {
        if (title != null && !title.isBlank()) {
            return title.strip();
        }
        if (summary != null && !summary.isBlank()) {
            return LongRunningTaskInitializer.taskTitle(summary);
        }
        if (currentTitle != null && !currentTitle.isBlank()) {
            return currentTitle.strip();
        }
        return "Long-running task";
    }

    static List<FeatureItem> featureItemsForReplacement(
            List<FeatureInput> featureInputs,
            List<FeatureItem> existingFeatures) {
        Map<String, FeatureItem> existing = existingFeatures.stream()
                .collect(java.util.stream.Collectors.toMap(
                        FeatureItem::id,
                        feature -> feature,
                        (left, right) -> left,
                        LinkedHashMap::new));
        return List.copyOf(Objects.requireNonNullElse(featureInputs, List.<FeatureInput>of())).stream()
                .map(feature -> featureItemForReplacement(feature, existing.get(feature.id())))
                .toList();
    }

    static List<KnownIssue> issueItemsForReplacement(
            List<IssueInput> issueInputs,
            String defaultDiscoveredIn) {
        Instant now = Instant.now();
        return List.copyOf(Objects.requireNonNullElse(issueInputs, List.<IssueInput>of())).stream()
                .map(issue -> {
                    String status = issue.status() == null || issue.status().isBlank()
                            ? "open"
                            : issue.status().strip().toLowerCase(java.util.Locale.ROOT);
                    return new KnownIssue(
                            issue.id(),
                            issue.description(),
                            issue.severity(),
                            status,
                            issue.discovered_in() == null || issue.discovered_in().isBlank()
                                    ? defaultDiscoveredIn
                                    : issue.discovered_in().strip(),
                            issue.verification_steps(),
                            now,
                            "resolved".equals(status) ? now : null);
                })
                .toList();
    }

    static String requireNonBlank(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String stripped = value.strip();
        if (stripped.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return stripped;
    }

    static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.isBlank() ? null : stripped;
    }

    static void putIfPresent(Map<String, String> details, String key, String value) {
        if (value != null && !value.isBlank()) {
            details.put(key, value.strip());
        }
    }

    private static FeatureItem featureItemForReplacement(FeatureInput feature, FeatureItem existing) {
        boolean canPreservePassedState = existing != null
                && existing.passes()
                && sameFeatureDefinition(feature, existing);
        if (Boolean.TRUE.equals(feature.passes()) && !canPreservePassedState) {
            throw new IllegalArgumentException(
                    "passes=true can only preserve an unchanged already-passed feature: " + feature.id());
        }
        boolean passes = feature.passes() == null ? canPreservePassedState : Boolean.TRUE.equals(feature.passes());
        List<String> evidence = passes ? existing.verificationEvidence() : List.of();
        return new FeatureItem(
                feature.id(),
                feature.category(),
                feature.priority(),
                feature.description(),
                feature.depends_on(),
                feature.verification_steps(),
                passes,
                evidence);
    }

    private static boolean sameFeatureDefinition(FeatureInput feature, FeatureItem existing) {
        return Objects.equals(feature.category(), existing.category())
                && Objects.equals(feature.priority(), existing.priority())
                && Objects.equals(feature.description(), existing.description())
                && Objects.equals(listOrEmpty(feature.depends_on()), existing.dependsOn())
                && Objects.equals(listOrEmpty(feature.verification_steps()), existing.verificationSteps());
    }

    private static List<String> listOrEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }
}
