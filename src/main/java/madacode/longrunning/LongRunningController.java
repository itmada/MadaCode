package madacode.longrunning;

import madacode.cli.InterruptController;
import madacode.core.model.Message;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.LongRunningTransitionRequest;
import madacode.core.session.SessionMode;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Single authority for long-running transition requests and applied state changes.
 */
public final class LongRunningController {

    private final TaskStoreFactory taskStoreFactory;
    private final LongRunningTaskInitializer.TaskIdGenerator taskIdGenerator;

    public LongRunningController() {
        this(LongRunningTaskStore::new, LongRunningTaskInitializer.TaskIdGenerator::defaultNewTaskId);
    }

    public LongRunningController(TaskStoreFactory taskStoreFactory) {
        this(taskStoreFactory, LongRunningTaskInitializer.TaskIdGenerator::defaultNewTaskId);
    }

    public LongRunningController(
            TaskStoreFactory taskStoreFactory,
            LongRunningTaskInitializer.TaskIdGenerator taskIdGenerator) {
        this.taskStoreFactory = Objects.requireNonNull(taskStoreFactory, "taskStoreFactory");
        this.taskIdGenerator = Objects.requireNonNull(taskIdGenerator, "taskIdGenerator");
    }

    public LongRunningTransitionRequest requestTransition(
            ConversationSession session,
            LongRunningStage targetStage,
            String reason,
            String summary,
            String planDelta,
            String requestedBy) {
        Objects.requireNonNull(session, "session");
        requireControlSession(session);
        LongRunningStage sourceStage = effectiveStage(session);
        LongRunningTransitionRequest request = LongRunningTransitionRequest.of(
                sourceStage,
                Objects.requireNonNull(targetStage, "targetStage").normalized(),
                reason,
                summary,
                planDelta,
                requestedBy);
        if (session.longRunningTaskId() == null) {
            ensureDraftTask(session, firstNonBlank(summary, session.longRunningTaskTitle(), ""));
        }
        validateRequest(session, request);
        session.setPendingLongRunningTransitionRequest(request);
        enqueueControllerEvent(session, "transition_request_pending", request,
                Map.of("requested_by", safe(requestedBy)));
        appendEvent(session, "transition_request_pending", request, true,
                "Pending transition request: " + request.sourceStage() + " -> " + request.targetStage() + ".",
                Map.of());
        appendControlNotice(session, "[long-running] Pending transition request: "
                + request.sourceStage() + " -> " + request.targetStage() + ".");
        return request;
    }

    public void rejectPendingRequest(ConversationSession session, String rejectedBy) {
        Objects.requireNonNull(session, "session");
        LongRunningTransitionRequest request = session.pendingLongRunningTransitionRequest()
                .orElseThrow(() -> new IllegalStateException("No pending long-running transition request."));
        session.flushPendingControllerEvents();
        appendControllerEvent(session, "transition_request_rejected", request,
                Map.of("rejected_by", safe(rejectedBy)));
        appendEvent(session, "transition_request_rejected", request, false,
                "Rejected transition request: " + request.sourceStage() + " -> " + request.targetStage() + ".",
                Map.of("rejectedBy", safe(rejectedBy)));
        appendControlNotice(session, "[long-running] Kept " + request.sourceStage()
                + " after user declined transition to " + request.targetStage() + ".");
        session.clearPendingLongRunningTransitionRequest();
    }

    public AppliedTransition applyPendingRequest(
            ConversationSession session,
            String approvedBy,
            InterruptController interruptController) {
        Objects.requireNonNull(session, "session");
        LongRunningTransitionRequest request = session.pendingLongRunningTransitionRequest()
                .orElseThrow(() -> new IllegalStateException("No pending long-running transition request."));
        session.clearPendingLongRunningTransitionRequest();
        return applyTransition(session, request, approvedBy, interruptController);
    }

    public AppliedTransition requestAndApply(
            ConversationSession session,
            LongRunningStage targetStage,
            String reason,
            String summary,
            String planDelta,
            String requestedBy,
            InterruptController interruptController) {
        requestTransition(session, targetStage, reason, summary, planDelta, requestedBy);
        return applyPendingRequest(session, requestedBy, interruptController);
    }

    private AppliedTransition applyTransition(
            ConversationSession session,
            LongRunningTransitionRequest request,
            String approvedBy,
            InterruptController interruptController) {
        requireControlSession(session);
        LongRunningStage previous = effectiveStage(session);
        LongRunningStage requestSource = request.sourceStage().normalized();
        if (previous != requestSource) {
            throw new IllegalStateException(
                    "Stale long-running transition request: requested from "
                            + requestSource + " but current stage is " + previous
                            + ". Request a new transition for the current stage.");
        }
        validateRequest(session, request);
        LongRunningTaskStore store = taskStore(session);
        String taskId = requireTaskId(session);
        LongRunningStage target = request.targetStage().normalized();
        LongRunningTransitions.Trigger trigger = LongRunningTransitions.Trigger.fromWire(request.reason())
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown long-running transition reason: " + request.reason()));

        switch (target) {
            case RUNNING -> {
                ensureExecutionTask(session, request.summary());
                store.markTaskExecuting(taskId);
                session.setLongRunningStage(LongRunningStage.RUNNING);
                session.setLongRunningReason(request.reason());
            }
            case INTERRUPT -> {
                store.markTaskInterrupted(taskId, request.reason());
                session.setLongRunningStage(LongRunningStage.INTERRUPT);
                session.setLongRunningReason(request.reason());
            }
            case DONE -> {
                LongRunningTransitions.TerminalAction action =
                        LongRunningTransitions.terminalActionFor(trigger);
                if (action == null) {
                    throw new IllegalStateException(
                            "DONE transition reason is not mechanically applicable by the control session: "
                                    + request.reason());
                }
                switch (action) {
                    case COMPLETE -> store.markTaskCompleted(taskId);
                    case CANCEL -> store.cancelTask(taskId);
                    case FAIL -> store.markTaskFailed(taskId);
                }
                session.setLongRunningStage(LongRunningStage.DONE);
                session.setLongRunningReason(request.reason());
            }
            case DRAFT -> throw new IllegalStateException(
                    "Long-running tasks do not transition back to DRAFT after creation.");
        }

        appendProgress(store, taskId, request, true);
        session.flushPendingControllerEvents();
        Map<String, String> appliedDetails = new LinkedHashMap<>();
        appliedDetails.put("approved_by", safe(approvedBy));
        if (target == LongRunningStage.INTERRUPT) {
            appliedDetails.put("interrupt_cause",
                    LongRunningTransitions.causeFor(trigger).name().toLowerCase(java.util.Locale.ROOT));
        }
        appendControllerEvent(session, "transition_applied", request, Map.copyOf(appliedDetails));
        appendEvent(session, "transition_applied", request, true,
                "Applied transition: " + previous + " -> " + target + " (" + request.reason() + ").",
                Map.of("approvedBy", safe(approvedBy)));
        appendControlNotice(session, "[long-running] " + previous + " -> " + target
                + " (" + request.reason() + ").");
        return new AppliedTransition(previous, target, request.reason());
    }

    private void validateRequest(ConversationSession session, LongRunningTransitionRequest request) {
        if (session.isLongRunningWorkerSession()) {
            throw new IllegalStateException("Worker session cannot request long-running transitions.");
        }
        LongRunningStage source = effectiveStage(session);
        LongRunningStage target = request.targetStage().normalized();
        LongRunningTransitions.Trigger trigger = LongRunningTransitions.Trigger.fromWire(request.reason())
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown long-running transition reason: " + request.reason()));
        if (!LongRunningTransitions.isAllowed(source, trigger, target)) {
            throw new IllegalStateException(
                    "Transition not allowed: " + LongRunningTransitions.describe(source, trigger, target)
                            + ". Legal from " + source + ": "
                            + LongRunningTransitions.legalTargetsFrom(source));
        }
        if ((source == LongRunningStage.DRAFT || source == LongRunningStage.INTERRUPT)
                && target == LongRunningStage.RUNNING) {
            LongRunningTaskStore store = taskStore(session);
            String taskId = requireTaskId(session);
            if (store.readFeatureList(taskId).isEmpty()) {
                throw new IllegalStateException(
                        "Cannot start long-running workers until feature_list.json is non-empty.");
            }
        }
    }

    private void ensureDraftTask(ConversationSession session, String expandedInput) {
        LongRunningTaskInitializer initializer =
                new LongRunningTaskInitializer(taskStore(session), taskIdGenerator);
        initializer.ensurePlanningTask(session, expandedInput);
        session.setLongRunningStage(LongRunningStage.DRAFT);
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
            LongRunningTransitionRequest request,
            boolean success,
            String message,
            Map<String, String> extraDetails) {
        try {
            String taskId = session.longRunningTaskId();
            if (taskId == null || taskId.isBlank()) {
                return;
            }
            LinkedHashMap<String, String> details = new LinkedHashMap<>();
            details.put("sourceStage", request.sourceStage().name());
            details.put("targetStage", request.targetStage().name());
            details.put("reason", request.reason());
            if (request.summary() != null) {
                details.put("summary", request.summary());
            }
            if (request.planDelta() != null) {
                details.put("planDelta", request.planDelta());
            }
            details.putAll(extraDetails);
            taskStore(session).appendEvent(taskId, new LongRunningTaskEvent(
                    Instant.now(),
                    type,
                    taskId,
                    session.sessionId(),
                    effectiveStage(session).name(),
                    request.reason(),
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
            LongRunningTransitionRequest request,
            boolean approved) {
        try {
            String line = "## " + Instant.now() + System.lineSeparator()
                    + "transition: " + request.sourceStage() + " -> " + request.targetStage() + System.lineSeparator()
                    + "reason: " + request.reason() + System.lineSeparator()
                    + "approved: " + approved + System.lineSeparator()
                    + (request.summary() == null ? "" : "summary: " + request.summary() + System.lineSeparator())
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
            LongRunningTransitionRequest request,
            Map<String, String> extraFields) {
        session.addControllerEvent("long-running", controllerEventFields(session, event, request, extraFields));
    }

    private static void enqueueControllerEvent(
            ConversationSession session,
            String event,
            LongRunningTransitionRequest request,
            Map<String, String> extraFields) {
        session.enqueueControllerEvent("long-running", controllerEventFields(session, event, request, extraFields));
    }

    private static LinkedHashMap<String, String> controllerEventFields(
            ConversationSession session,
            String event,
            LongRunningTransitionRequest request,
            Map<String, String> extraFields) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("event", event);
        fields.put("task", safe(session.longRunningTaskId()));
        fields.put("transition", request.sourceStage() + " -> " + request.targetStage());
        fields.put("reason", request.reason());
        if (request.summary() != null) {
            fields.put("summary", request.summary());
        }
        if (request.planDelta() != null) {
            fields.put("plan_delta", request.planDelta());
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

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return "";
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
}
