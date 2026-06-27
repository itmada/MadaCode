package madacode.longrunning;

import madacode.core.model.Message;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.LongRunningTransitionProposal;
import madacode.core.session.SessionMode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Single authority for validating and applying long-running state transitions.
 */
public final class LongRunningController {

    private final TaskStoreFactory taskStoreFactory;
    private final LongRunningTaskInitializer.TaskIdGenerator taskIdGenerator;
    private final ExecutionReadinessFactory executionReadinessFactory;

    public LongRunningController() {
        this(
                LongRunningTaskStore::new,
                LongRunningTaskInitializer.TaskIdGenerator::defaultNewTaskId,
                LongRunningExecutionReadiness::new);
    }

    public LongRunningController(TaskStoreFactory taskStoreFactory) {
        this(
                taskStoreFactory,
                LongRunningTaskInitializer.TaskIdGenerator::defaultNewTaskId,
                LongRunningExecutionReadiness::new);
    }

    public LongRunningController(
            TaskStoreFactory taskStoreFactory,
            LongRunningTaskInitializer.TaskIdGenerator taskIdGenerator) {
        this(taskStoreFactory, taskIdGenerator, LongRunningExecutionReadiness::new);
    }

    LongRunningController(
            TaskStoreFactory taskStoreFactory,
            LongRunningTaskInitializer.TaskIdGenerator taskIdGenerator,
            ExecutionReadinessFactory executionReadinessFactory) {
        this.taskStoreFactory = Objects.requireNonNull(taskStoreFactory, "taskStoreFactory");
        this.taskIdGenerator = Objects.requireNonNull(taskIdGenerator, "taskIdGenerator");
        this.executionReadinessFactory = Objects.requireNonNull(
                executionReadinessFactory,
                "executionReadinessFactory");
    }

    public LongRunningTransitionProposal prepareTransition(
            ConversationSession session,
            LongRunningStage targetStage,
            String reason,
            String summary,
            String planDelta,
            String requestedBy) {
        Objects.requireNonNull(session, "session");
        requireControlSession(session);
        LongRunningStage sourceStage = effectiveStage(session);
        LongRunningTransitionProposal proposal = LongRunningTransitionProposal.of(
                sourceStage,
                Objects.requireNonNull(targetStage, "targetStage").normalized(),
                reason,
                summary,
                planDelta,
                requestedBy);
        if (session.longRunningTaskId() == null || session.longRunningTaskId().isBlank()) {
            throw new IllegalStateException(
                    "Long-running environment files are not initialized. "
                            + "Initialize the task summary, feature list, known issues, and progress before "
                            + "applying a state transition.");
        }
        validateProposal(session, proposal);
        appendControllerEvent(session, "transition_confirmation_requested", proposal,
                Map.of("requested_by", safe(requestedBy)));
        appendEvent(session, "transition_confirmation_requested", proposal, true,
                "Transition confirmation requested: "
                        + proposal.sourceStage() + " -> " + proposal.targetStage() + ".",
                Map.of());
        return proposal;
    }

    public void recordRejectedTransition(
            ConversationSession session,
            LongRunningTransitionProposal proposal,
            String rejectedBy) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(proposal, "proposal");
        appendControllerEvent(session, "transition_confirmation_rejected", proposal,
                Map.of("rejected_by", safe(rejectedBy)));
        appendEvent(session, "transition_confirmation_rejected", proposal, false,
                "Rejected transition proposal: " + proposal.sourceStage() + " -> " + proposal.targetStage() + ".",
                Map.of("rejectedBy", safe(rejectedBy)));
        appendControlNotice(session, "[long-running] Kept " + proposal.sourceStage()
                + " after user declined transition to " + proposal.targetStage() + ".");
    }

    AppliedTransition prepareAndApply(
            ConversationSession session,
            LongRunningStage targetStage,
            String reason,
            String summary,
            String planDelta,
            String requestedBy) {
        LongRunningTransitionProposal transition = prepareTransition(
                session, targetStage, reason, summary, planDelta, requestedBy);
        return applyTransition(session, transition, requestedBy);
    }

    public AppliedTransition applyTransition(
            ConversationSession session,
            LongRunningTransitionProposal proposal,
            String approvedBy) {
        requireControlSession(session);
        LongRunningStage previous = effectiveStage(session);
        LongRunningStage proposalSource = proposal.sourceStage().normalized();
        if (previous != proposalSource) {
            throw new IllegalStateException(
                    "Stale long-running transition proposal: proposed from "
                            + proposalSource + " but current stage is " + previous
                            + ". Prepare a new transition for the current stage.");
        }
        validateProposal(session, proposal);
        LongRunningTaskStore store = taskStore(session);
        String taskId = requireTaskId(session);
        LongRunningTransitions.Trigger trigger = LongRunningTransitions.Trigger.fromWire(proposal.reason())
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown long-running transition reason: " + proposal.reason()));
        LongRunningLifecycleEvent event = LongRunningLifecycleEvent.controller(trigger);
        LongRunningLifecycleDecision decision = LongRunningLifecycleStateMachine.decide(previous, event);
        LongRunningStage target = decision.target();
        if (target != proposal.targetStage().normalized()) {
            throw new IllegalStateException(
                    "Transition proposal target " + proposal.targetStage().normalized()
                            + " does not match state machine target " + target
                            + " for reason " + proposal.reason() + ".");
        }

        switch (target) {
            case RUNNING -> {
                ensureExecutionTask(session, proposal.summary());
                session.setLongRunningStage(LongRunningStage.RUNNING);
                session.setLongRunningReason(proposal.reason());
            }
            case INTERRUPT -> {
                store.applyLifecycleEvent(taskId, event);
                session.setLongRunningStage(LongRunningStage.INTERRUPT);
                session.setLongRunningReason(proposal.reason());
            }
            case COMPLETED, CANCELLED, FAILED -> {
                store.applyLifecycleEvent(taskId, event);
                session.setLongRunningStage(target);
                session.setLongRunningReason(proposal.reason());
            }
            case DRAFT -> throw new IllegalStateException(
                    "Long-running tasks do not transition back to DRAFT after creation.");
        }

        appendProgress(store, taskId, proposal, true);
        session.flushPendingControllerEvents();
        Map<String, String> appliedDetails = new LinkedHashMap<>();
        appliedDetails.put("approved_by", safe(approvedBy));
        if (target == LongRunningStage.INTERRUPT) {
            appliedDetails.put("interrupt_cause",
                    decision.interruptCause().name().toLowerCase(java.util.Locale.ROOT));
        }
        appendControllerEvent(session, "transition_applied", proposal, Map.copyOf(appliedDetails));
        appendEvent(session, "transition_applied", proposal, true,
                "Applied transition: " + previous + " -> " + target + " (" + proposal.reason() + ").",
                Map.of("approvedBy", safe(approvedBy)));
        appendControlNotice(session, "[long-running] " + previous + " -> " + target
                + " (" + proposal.reason() + ").");
        return new AppliedTransition(previous, target, proposal.reason());
    }

    private void validateProposal(ConversationSession session, LongRunningTransitionProposal proposal) {
        if (session.isLongRunningWorkerSession()) {
            throw new IllegalStateException("Worker session cannot manage long-running transitions.");
        }
        LongRunningStage source = effectiveStage(session);
        LongRunningStage target = proposal.targetStage().normalized();
        LongRunningTransitions.Trigger trigger = LongRunningTransitions.Trigger.fromWire(proposal.reason())
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown long-running transition reason: " + proposal.reason()));
        LongRunningLifecycleDecision decision = LongRunningLifecycleStateMachine.decide(
                source, LongRunningLifecycleEvent.controller(trigger));
        if (decision.target() != target) {
            throw new IllegalStateException(
                    "Transition not allowed: " + LongRunningTransitions.describe(source, trigger, target)
                            + ". Legal from " + source + ": "
                            + LongRunningTransitions.legalTargetsFrom(source));
        }
        if ((source == LongRunningStage.DRAFT || source == LongRunningStage.INTERRUPT)
                && target == LongRunningStage.RUNNING) {
            LongRunningTaskStore store = taskStore(session);
            String taskId = requireTaskId(session);
            LongRunningExecutionReadiness.Result readiness =
                    executionReadinessFactory.create(store).evaluate(taskId);
            if (!readiness.isReady()) {
                throw new IllegalStateException(readiness.summary());
            }
        }
    }

    private void ensureExecutionTask(ConversationSession session, String expandedInput) {
        LongRunningTaskInitializer initializer =
                new LongRunningTaskInitializer(taskStore(session), taskIdGenerator);
        initializer.ensureExecutionTask(session, expandedInput == null ? "" : expandedInput);
        session.setLongRunningStage(LongRunningStage.RUNNING);
    }

    private void appendEvent(
            ConversationSession session,
            String type,
            LongRunningTransitionProposal proposal,
            boolean success,
            String message,
            Map<String, String> extraDetails) {
        try {
            String taskId = session.longRunningTaskId();
            if (taskId == null || taskId.isBlank()) {
                return;
            }
            LinkedHashMap<String, String> details = new LinkedHashMap<>();
            details.put("sourceStage", proposal.sourceStage().name());
            details.put("targetStage", proposal.targetStage().name());
            details.put("reason", proposal.reason());
            if (proposal.summary() != null) {
                details.put("summary", proposal.summary());
            }
            if (proposal.planDelta() != null) {
                details.put("planDelta", proposal.planDelta());
            }
            details.putAll(extraDetails);
            taskStore(session).appendEvent(taskId, new LongRunningTaskEvent(
                    Instant.now(),
                    type,
                    taskId,
                    session.sessionId(),
                    effectiveStage(session).name(),
                    proposal.reason(),
                    success,
                    message,
                    Map.copyOf(details)));
        } catch (RuntimeException ignored) {
            // Best-effort diagnostics only.
        }
    }

    private static void appendProgress(
            LongRunningTaskStore store,
            String taskId,
            LongRunningTransitionProposal proposal,
            boolean approved) {
        try {
            String line = "## " + Instant.now() + System.lineSeparator()
                    + "transition: " + proposal.sourceStage() + " -> " + proposal.targetStage() + System.lineSeparator()
                    + "reason: " + proposal.reason() + System.lineSeparator()
                    + "approved: " + approved + System.lineSeparator()
                    + (proposal.summary() == null ? "" : "summary: " + proposal.summary() + System.lineSeparator())
                    + System.lineSeparator();
            store.appendProgress(taskId, line);
        } catch (RuntimeException ignored) {
            // Best-effort diagnostics only.
        }
    }

    private static void appendControlNotice(ConversationSession session, String text) {
        session.addMessage(Message.system(text));
    }

    private static void appendControllerEvent(
            ConversationSession session,
            String event,
            LongRunningTransitionProposal proposal,
            Map<String, String> extraFields) {
        session.addControllerEvent("long-running", controllerEventFields(session, event, proposal, extraFields));
    }

    private static LinkedHashMap<String, String> controllerEventFields(
            ConversationSession session,
            String event,
            LongRunningTransitionProposal proposal,
            Map<String, String> extraFields) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("event", event);
        fields.put("task", safe(session.longRunningTaskId()));
        fields.put("transition", proposal.sourceStage() + " -> " + proposal.targetStage());
        fields.put("reason", proposal.reason());
        if (proposal.summary() != null) {
            fields.put("summary", proposal.summary());
        }
        if (proposal.planDelta() != null) {
            fields.put("plan_delta", proposal.planDelta());
        }
        fields.putAll(extraFields);
        return fields;
    }

    private static void requireControlSession(ConversationSession session) {
        if (session.workflowMode() != SessionMode.LONG_RUNNING) {
            throw new IllegalStateException("Long-running mode is not active for this session.");
        }
        if (session.isLongRunningWorkerSession()) {
            throw new IllegalStateException("Worker session cannot manage long-running transitions.");
        }
    }

    private static LongRunningStage effectiveStage(ConversationSession session) {
        LongRunningStage stage = session.longRunningStage();
        return stage == null ? LongRunningStage.DRAFT : stage.normalized();
    }

    private LongRunningTaskStore taskStore(ConversationSession session) {
        return taskStoreFactory.create(session.workingDirectory());
    }

    private static String requireTaskId(ConversationSession session) {
        String taskId = session.longRunningTaskId();
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalStateException("No active long-running task on session.");
        }
        return taskId;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public record AppliedTransition(
            LongRunningStage sourceStage,
            LongRunningStage targetStage,
            String reason) {
    }

    @FunctionalInterface
    public interface TaskStoreFactory {
        LongRunningTaskStore create(Path projectDir);
    }

    @FunctionalInterface
    interface ExecutionReadinessFactory {
        LongRunningExecutionReadiness create(LongRunningTaskStore store);
    }
}
