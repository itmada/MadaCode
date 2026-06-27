package madacode.longrunning;

import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.tool.Tool;
import madacode.tool.ToolNames;
import madacode.tool.access.ToolCapabilityProfile;
import madacode.tool.access.WorkflowCapabilityPolicy;
import madacode.tool.access.WorkflowVote;

import java.util.Set;

/**
 * Long-running workflow overlay for {@link madacode.tool.access.ToolAccessResolver}.
 *
 * <p>It contributes exactly two things and nothing else:
 * <ol>
 *   <li><b>Worker capability</b> ({@link #sessionProfile}): a long-running worker
 *       session may use only {@link #WORKER_TOOLS}, including {@code update_plan}
 *       for visible current-cycle progress; once it has reported (or is not in
 *       the RUNNING stage) it may use nothing.</li>
 *   <li><b>Control-session lifecycle gating</b> ({@link #lifecycleVote}): the
 *       long-running environment tools and {@code longrun_state_transition}
 *       are exposed to the control session at the stages where they make sense;
 *       {@code worker_report} is worker-only.</li>
 * </ol>
 *
 * <p>Ordinary control-session tools are not restricted here; the control session is
 * the main agent and uses the normal capability/exposure rules subject to the
 * permission gate.
 */
public final class LongRunningCapabilityPolicy implements WorkflowCapabilityPolicy {

    /**
     * The complete set of tools a long-running worker session may use. The
     * worker uses {@code update_plan} only for the visible current execution
     * checklist; durable task-store progress is written through
     * {@code longrun_environment_update}. This set is the single source of truth for
     * worker capability and is pinned by
     * {@code LongRunningCapabilityPolicyTest}. Keep it code-defined unless there is
     * an explicit security review for configurable worker capabilities.
     */
    static final Set<String> WORKER_TOOLS = Set.of(
            ToolNames.FILE_READ,
            ToolNames.GLOB,
            ToolNames.GREP,
            ToolNames.FILE_WRITE,
            ToolNames.FILE_EDIT,
            ToolNames.BASH,
            ToolNames.UPDATE_PLAN,
            ToolNames.LONGRUN_ENVIRONMENT_READ,
            ToolNames.LONGRUN_ENVIRONMENT_UPDATE,
            ToolNames.WORKER_REPORT);

    @Override
    public ToolCapabilityProfile sessionProfile(ConversationSession session) {
        if (session == null || !session.isLongRunningWorkerSession()) {
            // Control and common sessions keep the unrestricted default profile.
            return null;
        }
        // A worker runs exactly one cycle and stops after reporting: outside the
        // RUNNING stage, or once a report exists, no tools remain usable.
        if (session.lastWorkerReport().isPresent()
                || session.longRunningStage() != LongRunningStage.RUNNING) {
            return ToolCapabilityProfile.explicitAllowList("longrun-worker-idle", Set.of(), false);
        }
        return ToolCapabilityProfile.explicitAllowList("longrun-worker", WORKER_TOOLS, false);
    }

    @Override
    public WorkflowVote lifecycleVote(Tool<?> tool, ConversationSession session) {
        if (tool == null || !isLifecycleTool(tool.name())) {
            return WorkflowVote.abstain();
        }
        // Worker lifecycle tools are governed by the worker capability profile.
        if (session != null && session.isLongRunningWorkerSession()) {
            return WorkflowVote.abstain();
        }
        if (session == null || session.workflowMode() != SessionMode.LONG_RUNNING) {
            return WorkflowVote.deny("Long-running mode is not active for this session.");
        }
        LongRunningStage stage = session.longRunningStage();
        if (stage == null) {
            return WorkflowVote.deny("No long-running stage is active for this session.");
        }
        return switch (tool.name()) {
            case ToolNames.WORKER_REPORT -> WorkflowVote.deny(
                    "worker_report is only available in a worker session. Current stage: " + stage);
            case ToolNames.LONGRUN_ENVIRONMENT_READ -> WorkflowVote.expose();
            case ToolNames.LONGRUN_ENVIRONMENT_UPDATE -> draftOrInterrupt(stage)
                    ? WorkflowVote.expose()
                    : WorkflowVote.deny(tool.name() + " is only available in the control session "
                            + "while the task is DRAFT or INTERRUPT. Current stage: " + stage);
            case ToolNames.LONGRUN_STATE_TRANSITION -> draftOrInterrupt(stage)
                    ? WorkflowVote.expose()
                    : WorkflowVote.deny("longrun_state_transition is only available in the "
                            + "control session while the task is DRAFT or INTERRUPT. Current stage: " + stage);
            default -> WorkflowVote.abstain();
        };
    }

    private static boolean draftOrInterrupt(LongRunningStage stage) {
        return stage == LongRunningStage.DRAFT || stage == LongRunningStage.INTERRUPT;
    }

    private static boolean isLifecycleTool(String name) {
        return ToolNames.WORKER_REPORT.equals(name)
                || ToolNames.LONGRUN_ENVIRONMENT_READ.equals(name)
                || ToolNames.LONGRUN_ENVIRONMENT_UPDATE.equals(name)
                || ToolNames.LONGRUN_STATE_TRANSITION.equals(name);
    }
}
