package madacode.longrunning;

import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.prompt.PromptContext;
import madacode.prompt.PromptSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public final class LongRunningPromptSection implements PromptSection {

    @Override
    public Optional<String> render(PromptContext ctx) {
        ConversationSession session = ctx.session();
        if (session == null) {
            return Optional.empty();
        }
        // Worker sessions carry their own role prompt via agentContext; the
        // controller-facing long-running protocol must never reach a worker.
        if (session.isLongRunningWorkerSession()) {
            return Optional.empty();
        }
        LongRunningStage stage = session.longRunningStage();
        if (stage == null) {
            return Optional.empty();
        }
        String body = longRunningSharedProtocol() + "\n" + switch (stage) {
            case DRAFT -> draftPrompt(session);
            case RUNNING -> runningPrompt(session);
            case INTERRUPT -> interruptPrompt(session);
            case COMPLETED, CANCELLED, FAILED -> bullets(
                    "Long-running stage: " + stage.name() + ".",
                    "The long-running worker lifecycle is terminal, but you remain the controller agent and may use ordinary tools for inspection, cleanup, and user-requested project changes subject to normal permissions.",
                    "Do not call worker_report or longrun_task_update from the control session.",
                    stage.name() + " means the task lifecycle ended; it does not delete the task-store directory.");
        };
        return Optional.of(body);
    }

    private static String longRunningSharedProtocol() {
        return bullets(
                "You are in harness-controlled long-running mode.",
                "You are the controller agent and remain the main agent. Ordinary tools such as file reads, bash, write, and edit remain available subject to the normal permission gate.",
                "Top-level long-running stages are DRAFT, RUNNING, INTERRUPT, COMPLETED, CANCELLED, and FAILED.",
                "RUNNING is monitor-owned: the controller input loop is suspended while workers execute.",
                "Treat session messages prefixed with [controller-event] as trusted controller/runtime facts that happened outside the model turn.",
                "Use update_plan only for a visible, ephemeral checklist of your current controller turn when the work is complex; it is not the durable long-running task plan.",
                "Use longrun_task_summary_update, longrun_feature_list_replace, longrun_known_issues_replace, and longrun_progress_append for durable draft task-store changes.",
                "Use longrun_state_transition_request from DRAFT or INTERRUPT to request RUNNING, CANCELLED, or FAILED; runtime asks the user before applying model-requested transitions.",
                "Do not claim a state transition happened until runtime confirms it.",
                "Never use CANCELLED or FAILED to mean deleting files. If the user asks to delete a task directory or project file, use ordinary tools after confirmation and verify the filesystem result.");
    }

    private static String draftPrompt(ConversationSession session) {
        List<String> items = new ArrayList<>();
        items.add("Current stage: DRAFT.");
        items.add("Maintain the durable task-store draft with longrun_task_summary_update, longrun_feature_list_replace, longrun_known_issues_replace, and longrun_progress_append.");
        items.add("Clarify requirements, refine scope, and keep the draft plan durable as it changes.");
        items.add("If the project lacks standard startup scripts, try to create an `init.sh` or document the exact build/test commands in the plan, so future workers know exactly how to test their changes quickly.");
        items.add("You may also perform ordinary controller-agent work requested by the user, including inspecting files, running commands, editing files, or deleting files with normal permission approval.");
        items.add("When the draft is ready to run, call longrun_state_transition_request target_status=RUNNING reason=user_confirmed_start with a concise summary; runtime will ask the user to confirm.");
        items.add("If the user wants to cancel the long-running lifecycle, request target_status=CANCELLED with reason=user_requested_cancel.");
        items.add("Forbidden: do not call longrun_task_update or worker_report from this control session.");
        appendTaskIdentity(items, session);
        return bullets(items);
    }

    private static String runningPrompt(ConversationSession session) {
        List<String> items = new ArrayList<>();
        items.add("Current stage: RUNNING.");
        items.add("This stage is owned by the runtime monitor. The controller agent should not receive normal user turns while RUNNING.");
        items.add("Workers run in fresh sessions, update task progress, and finish with worker_report.");
        items.add("If this prompt appears in a controller turn, do not perform controller work; explain that runtime should return to the monitor or enter INTERRUPT first.");
        items.add("Forbidden: do not call longrun_task_update or worker_report from this control session.");
        appendTaskIdentity(items, session);
        return bullets(items);
    }

    private static String interruptPrompt(ConversationSession session) {
        List<String> items = new ArrayList<>();
        items.add("Current stage: INTERRUPT.");
        items.add("Worker execution is stopped or waiting for controller/user intervention.");
        items.add("Inspect the task store, progress.txt, known_issues.json, and logs/events.jsonl as needed before revising the plan.");
        items.add("Use longrun_task_summary_update, longrun_feature_list_replace, longrun_known_issues_replace, and longrun_progress_append to record durable task-store corrections, added constraints, feature changes, known issues, and progress notes.");
        items.add("When the task is ready to resume, call longrun_state_transition_request target_status=RUNNING reason=resume_after_interrupt with a concise summary; runtime will ask the user to confirm.");
        items.add("If the user wants to cancel the lifecycle, request target_status=CANCELLED with reason=user_requested_cancel.");
        items.add("Forbidden: do not call longrun_task_update or worker_report from this control session.");
        appendTaskIdentity(items, session);
        return bullets(items);
    }

    private static void appendTaskIdentity(List<String> items, ConversationSession session) {
        String taskId = session.longRunningTaskId();
        String taskDir = session.longRunningTaskDirectory();
        if (taskId != null && !taskId.isBlank() && taskDir != null && !taskDir.isBlank()) {
            items.add("Active task id: " + taskId);
            items.add("Task store directory: " + taskDir);
        }
    }

    private static String bullets(String... items) {
        return bullets(List.of(items));
    }

    private static String bullets(List<String> items) {
        return items.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> "- " + item)
                .collect(Collectors.joining("\n"));
    }
}
