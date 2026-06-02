package madacode.longrunning;

import madacode.core.session.LongRunningStage;
import madacode.core.session.LongRunningTurnAssignment;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Verifies whether an EXECUTING turn advanced its harness-assigned target.
 *
 * <p>This verifier is intentionally non-terminal. It records structured
 * results and human-readable warnings, leaving retry/escalation policy to a
 * later harness stage.
 */
public final class LongRunningPostTurnVerifier {

    private final LongRunningTaskStore store;

    public LongRunningPostTurnVerifier(LongRunningTaskStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public VerificationResult verify(String taskId, String sessionId, LongRunningTurnAssignment assignment) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(assignment, "assignment");

        VerificationResult result = switch (assignment.kind()) {
            case SEED_FEATURE_LIST -> verifySeedFeatureList(taskId);
            case ISSUE -> verifyIssue(taskId, assignment);
            case FEATURE -> verifyFeature(taskId, assignment);
            case COMPLETE_TASK -> verifyCompleteTask(taskId);
            case BLOCKED -> verifyBlocked(taskId);
        };
        recordResult(taskId, sessionId, assignment, result);
        return result;
    }

    private VerificationResult verifySeedFeatureList(String taskId) {
        boolean seeded = !store.readFeatureList(taskId).isEmpty();
        return seeded
                ? VerificationResult.success("Initial feature list is present.")
                : VerificationResult.failure("Assigned target was not advanced: feature_list.json is still empty.");
    }

    private VerificationResult verifyIssue(String taskId, LongRunningTurnAssignment assignment) {
        String issueId = assignment.id();
        boolean stillActive = store.readKnownIssues(taskId).stream()
                .anyMatch(issue -> issue.id().equals(issueId)
                        && ("open".equals(issue.status()) || "blocked".equals(issue.status())));
        if (!stillActive) {
            return VerificationResult.success("Assigned issue is no longer active.");
        }
        boolean relevantUpdate = taskUpdatesAfterAssignment(taskId).stream()
                .anyMatch(event -> Boolean.TRUE.equals(event.success())
                        && issueId.equals(event.details().get("issueId"))
                        && ("resolve_issue".equals(event.action())
                            || "update_issue_status".equals(event.action())
                            || "record_issue".equals(event.action())));
        return relevantUpdate
                ? VerificationResult.success("Assigned issue received a relevant task update.")
                : VerificationResult.failure("Assigned issue was not resolved or updated: " + issueId + ".");
    }

    private VerificationResult verifyFeature(String taskId, LongRunningTurnAssignment assignment) {
        String featureId = assignment.id();
        boolean passed = store.readFeatureList(taskId).stream()
                .anyMatch(feature -> feature.id().equals(featureId) && feature.passes());
        if (passed) {
            return VerificationResult.success("Assigned feature is marked passed.");
        }
        boolean relevantUpdate = taskUpdatesAfterAssignment(taskId).stream()
                .anyMatch(event -> isFeatureProgressForAssignment(event, assignment));
        return relevantUpdate
                ? VerificationResult.success("Assigned feature turn recorded target-scoped task-store progress.")
                : VerificationResult.failure("Assigned feature turn completed without task-store progress: " + featureId + ".");
    }

    private VerificationResult verifyCompleteTask(String taskId) {
        LongRunningTaskMetadata metadata = store.loadTask(taskId);
        boolean completed = "completed".equals(metadata.status())
                || LongRunningStage.COMPLETED.name().equals(metadata.stage());
        return completed
                ? VerificationResult.success("Task is marked completed.")
                : VerificationResult.failure("Task completion was assigned but task is not completed.");
    }

    private VerificationResult verifyBlocked(String taskId) {
        boolean relevantUpdate = taskUpdatesAfterAssignment(taskId).stream()
                .anyMatch(event -> Boolean.TRUE.equals(event.success())
                        && matchesAssignedTarget(event, LongRunningTurnAssignment.Kind.BLOCKED, null)
                        && ("append_progress".equals(event.action())
                            || "record_issue".equals(event.action())));
        return relevantUpdate
                ? VerificationResult.success("Blocked assignment recorded task-store progress.")
                : VerificationResult.failure("Blocked assignment did not record progress or a known issue.");
    }

    private static boolean isFeatureProgressForAssignment(
            LongRunningTaskEvent event,
            LongRunningTurnAssignment assignment) {
        if (!Boolean.TRUE.equals(event.success())
                || !matchesAssignedTarget(event, assignment.kind(), assignment.id())) {
            return false;
        }
        return switch (event.action()) {
            case "append_progress", "record_issue" -> true;
            case "mark_feature_passed" -> assignment.id().equals(event.details().get("featureId"));
            default -> false;
        };
    }

    private static boolean matchesAssignedTarget(
            LongRunningTaskEvent event,
            LongRunningTurnAssignment.Kind kind,
            String targetId) {
        Map<String, String> details = event.details();
        if (!kind.name().equals(details.get("assignedKind"))) {
            return false;
        }
        String assignedTargetId = normalize(details.get("assignedTargetId"));
        return Objects.equals(normalize(targetId), assignedTargetId);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isBlank() ? null : normalized;
    }

    private List<LongRunningTaskEvent> taskUpdatesAfterAssignment(String taskId) {
        List<LongRunningTaskEvent> events = store.readEvents(taskId);
        int start = 0;
        for (int i = events.size() - 1; i >= 0; i--) {
            if ("target_assigned".equals(events.get(i).type())) {
                start = i + 1;
                break;
            }
        }
        return events.subList(start, events.size()).stream()
                .filter(event -> "task_update".equals(event.type()))
                .toList();
    }

    private void recordResult(
            String taskId,
            String sessionId,
            LongRunningTurnAssignment assignment,
            VerificationResult result) {
        store.appendEvent(taskId, LongRunningTaskEvent.of(
                "assignment_verified",
                taskId,
                sessionId,
                LongRunningStage.EXECUTING.name(),
                assignment.kind().name(),
                result.success(),
                result.message(),
                Map.of(
                        "targetId", assignment.id() == null ? "" : assignment.id(),
                        "reason", assignment.reason() == null ? "" : assignment.reason())));
        if (!result.success()) {
            store.appendProgress(taskId,
                    "[HARNESS WARNING] " + result.message() + System.lineSeparator());
        }
    }

    public record VerificationResult(boolean success, String message) {
        static VerificationResult success(String message) {
            return new VerificationResult(true, message);
        }

        static VerificationResult failure(String message) {
            return new VerificationResult(false, message);
        }
    }
}
