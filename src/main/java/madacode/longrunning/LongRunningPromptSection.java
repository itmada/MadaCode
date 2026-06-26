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
        String body = sharedCore() + "\n" + switch (stage) {
            case DRAFT -> draftPrompt(session);
            case RUNNING -> runningPrompt(session);
            case INTERRUPT -> interruptPrompt(session);
            case COMPLETED, CANCELLED, FAILED -> terminalPrompt(session, stage);
        };
        return Optional.of(body);
    }

    private static String sharedCore() {
        return bullets(
                "You are in long-running mode, which splits work between two roles: the Controller (you) and workers. You are the Controller, the main agent that talks with the user.",
                "Workers are background executors. Once the task is RUNNING, the runtime repeatedly spawns fresh, isolated worker sessions that carry out the plan; workers never talk to the user.",
                "Your core job is to work with the user to agree on the task details, then initialize the long-running environment from the agreed plan after the user confirms execution can begin.",
                "As Controller, you generally should not execute the task yourself. Only use ordinary tools such as file reads, bash, and edits for hands-on changes or task execution when the user explicitly asks.",
                "To start, resume, or cancel the run, call longrun_state_transition_request to propose it after the long-running environment files are fully initialized. This does not change state directly — the runtime asks the user to confirm first, so do not claim a transition happened until it is confirmed.");
    }

    private static String draftPrompt(ConversationSession session) {
        List<String> items = new ArrayList<>();
        items.add("Current stage: DRAFT — you are shaping the task before any work starts.");
        items.add("First, clarify the requirements and narrow the scope with the user. When the plan is clear enough, ask whether execution can begin.");
        items.add("Only after the user gives a clear yes, initialize the long-running environment files from the agreed plan: task summary, feature list, known issues, progress, and any build/test commands or init.sh details workers need.");
        items.add("After the long-running environment files are fully initialized, propose starting the run by calling longrun_state_transition_request target_status=RUNNING reason=user_confirmed_start with a short summary. Call it alone, stop after calling it, and let the runtime ask the user for approval.");
        appendTaskIdentity(items, session);
        return bullets(items);
    }

    private static String runningPrompt(ConversationSession session) {
        List<String> items = new ArrayList<>();
        items.add("Current stage: RUNNING — workers are executing in the background, and you normally will not receive user turns here.");
        items.add("If you do get a turn while RUNNING, do not act as Controller until the runtime returns to the monitor or the task enters INTERRUPT.");
        appendTaskIdentity(items, session);
        return bullets(items);
    }

    private static String interruptPrompt(ConversationSession session) {
        List<String> items = new ArrayList<>();
        items.add("Current stage: INTERRUPT — the run paused and needs you.");
        items.add("Find out what happened: read progress.txt, known_issues.json, and logs/events.jsonl in the task store.");
        items.add("Fix the plan accordingly — correct the summary, features, known issues, or progress with the longrun_* plan tools.");
        items.add("When it is ready to continue, propose resuming: longrun_state_transition_request target_status=RUNNING reason=resume_after_interrupt, with a short summary.");
        appendTaskIdentity(items, session);
        return bullets(items);
    }

    private static String terminalPrompt(ConversationSession session, LongRunningStage stage) {
        List<String> items = new ArrayList<>();
        items.add("Current stage: " + stage.name() + " — the run lifecycle has ended.");
        items.add("You are still the Controller and may use ordinary tools for inspection, cleanup, or any changes the user asks for, subject to the normal permission gate.");
        items.add("Ending the run does not delete the task-store directory; only remove files if the user explicitly asks.");
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
