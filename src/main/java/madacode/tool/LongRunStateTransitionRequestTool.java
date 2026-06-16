package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.core.model.ToolResult;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.LongRunningTransitionRequest;
import madacode.core.session.SessionMode;
import madacode.longrunning.LongRunningController;

import java.util.Objects;

public final class LongRunStateTransitionRequestTool
        implements Tool<LongRunStateTransitionRequestTool.Input> {

    public record Input(
            String target_status,
            String reason,
            String summary,
            String plan_delta) {}

    private final LongRunningController controller;

    public LongRunStateTransitionRequestTool() {
        this(new LongRunningController());
    }

    LongRunStateTransitionRequestTool(LongRunningController controller) {
        this.controller = Objects.requireNonNull(controller, "controller");
    }

    @Override
    public String name() {
        return "longrun_state_transition_request";
    }

    @Override
    public String description() {
        return "Request a long-running state transition for the control session. "
                + "This does not change state directly; runtime will ask the user to confirm first.";
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
        return true;
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("target_status", ToolSchemas.stringEnumProperty(
                mapper,
                "Requested target status for the control session",
                "RUNNING", "CANCELLED", "FAILED"));
        properties.set("reason", ToolSchemas.stringEnumProperty(
                mapper,
                "Why this transition is being requested",
                madacode.longrunning.LongRunningTransitions.requestableReasonWires()));
        properties.set("summary", ToolSchemas.stringProperty(
                mapper, "Brief user-facing summary for the requested transition."));
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
            return failed("Worker session cannot request long-running state transitions.");
        }
        LongRunningStage targetStage = LongRunningStage.fromWire(input.target_status()).orElse(null);
        if (targetStage == null) {
            return failed("Unsupported target_status: " + input.target_status());
        }
        try {
            LongRunningTransitionRequest request = controller.requestTransition(
                    session,
                    targetStage,
                    input.reason(),
                    input.summary(),
                    input.plan_delta(),
                    name());
            return succeeded("Pending transition request recorded: "
                    + request.sourceStage() + " -> " + request.targetStage()
                    + ". Runtime will ask for confirmation.");
        } catch (RuntimeException exception) {
            return failed(exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage());
        }
    }

    private static ToolResult succeeded(String output) {
        return new ToolResult("longrun_state_transition_request", true, output);
    }

    private static ToolResult failed(String output) {
        return new ToolResult("longrun_state_transition_request", false, output);
    }
}
