package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.core.model.ToolResult;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.LongRunningTransitionProposal;
import madacode.core.session.SessionMode;
import madacode.longrunning.LongRunningController;

import java.util.Objects;

public final class LongRunStateTransitionTool
        implements Tool<LongRunStateTransitionTool.Input> {

    public record Input(
            String target_status,
            String reason,
            String summary,
            String plan_delta) {}

    private final LongRunningController controller;

    public LongRunStateTransitionTool() {
        this(new LongRunningController());
    }

    LongRunStateTransitionTool(LongRunningController controller) {
        this.controller = Objects.requireNonNull(controller, "controller");
    }

    @Override
    public String name() {
        return ToolNames.LONGRUN_STATE_TRANSITION;
    }

    @Override
    public String description() {
        return "Controller: ask the user to approve and then apply a long-running state transition, such as "
                + "DRAFT->RUNNING, INTERRUPT->RUNNING, or cancelling/failing the task. "
                + "Use this only after the long-running environment is fully initialized and ready. "
                + "This tool must be called alone; if batched with any other tool, the whole batch is rejected. "
                + "If the user approves, the state transition is applied before the tool returns and the current "
                + "turn ends so the runtime can continue with the next workflow step.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean isPlanModeSafe() {
        return false;
    }

    @Override
    public boolean mustRunAlone() {
        return true;
    }

    @Override
    public String runAloneFailureMessage() {
        return "状态流转工具需要单独调用，不能和其他工具同批调用。";
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("target_status", ToolSchemas.stringEnumProperty(
                mapper,
                "Target status to apply if the user approves.",
                "RUNNING", "CANCELLED", "FAILED"));
        properties.set("reason", ToolSchemas.stringEnumProperty(
                mapper,
                "Why this transition is being applied",
                madacode.longrunning.LongRunningTransitions.requestableReasonWires()));
        properties.set("summary", ToolSchemas.stringProperty(
                mapper, "Brief user-facing summary shown in the approval prompt."));
        properties.set("plan_delta", ToolSchemas.stringProperty(
                mapper, "Optional note describing what changed in the plan."));
        return ToolSchemas.objectSchema(mapper, properties, "target_status", "reason");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        Objects.requireNonNull(input, "input");
        ConversationSession session = context.session();
        if (session.workflowMode() != SessionMode.LONG_RUNNING) {
            return failed("Long-running mode is not active for this session.");
        }
        if (session.isLongRunningWorkerSession()) {
            return failed("Worker session cannot manage long-running state transitions.");
        }
        LongRunningStage targetStage = LongRunningStage.fromWire(input.target_status()).orElse(null);
        if (targetStage == null) {
            return failed("Unsupported target_status: " + input.target_status());
        }
        if (!context.userPrompts().isAvailable()) {
            return failed("Cannot ask the user to approve the long-running state transition in this runtime.");
        }
        try {
            LongRunningTransitionProposal transition = controller.prepareTransition(
                    session,
                    targetStage,
                    input.reason(),
                    input.summary(),
                    input.plan_delta(),
                    name());
            boolean approved = context.userPrompts().confirm(transitionPrompt(transition));
            if (!approved) {
                controller.recordRejectedTransition(session, transition, "user");
                return succeeded("用户拒绝状态流转，当前任务保持 "
                        + transition.sourceStage() + "。请继续和用户商讨方案。");
            }
            LongRunningController.AppliedTransition applied =
                    controller.applyTransition(session, transition, "user");
            return yielded("用户已批准状态流转，状态已流转至 "
                    + applied.targetStage()
                    + "。当前 turn 结束，workflow 交给 runtime 继续执行。");
        } catch (RuntimeException exception) {
            return failed(exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage());
        }
    }

    private static String transitionPrompt(LongRunningTransitionProposal transition) {
        LongRunningStage source = transition.sourceStage().normalized();
        LongRunningStage target = transition.targetStage().normalized();
        String suffix = transition.summary() == null ? "" : "\n" + transition.summary();
        if (source == LongRunningStage.DRAFT && target == LongRunningStage.RUNNING) {
            return "Start this long-running task now?" + suffix;
        }
        if (source == LongRunningStage.INTERRUPT && target == LongRunningStage.RUNNING) {
            return "Resume this long-running task now?" + suffix;
        }
        if (target.isTerminal()) {
            return "Mark this long-running task " + target.name() + "?" + suffix;
        }
        return "Apply long-running transition " + source + " -> " + target + "?" + suffix;
    }

    private static ToolResult succeeded(String output) {
        return new ToolResult(ToolNames.LONGRUN_STATE_TRANSITION, true, output);
    }

    private static ToolResult yielded(String output) {
        return new ToolResult(
                ToolNames.LONGRUN_STATE_TRANSITION,
                true,
                output,
                ToolResult.TurnControl.YIELD_TO_RUNTIME);
    }

    private static ToolResult failed(String output) {
        return new ToolResult(ToolNames.LONGRUN_STATE_TRANSITION, false, output);
    }
}
