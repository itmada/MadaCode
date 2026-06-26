package madacode.tool;

import madacode.core.engine.ToolUseContext;
import madacode.core.model.MetaEvent;
import madacode.core.model.ToolResult;
import madacode.plan.CurrentPlan;
import madacode.plan.PlanStep;
import madacode.plan.PlanStepStatus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

public final class UpdatePlanTool implements Tool<UpdatePlanTool.Input> {

    public record Input(String explanation, List<PlanStepInput> plan) {}

    public record PlanStepInput(String step, String status) {}

    @Override
    public String name() {
        return ToolNames.UPDATE_PLAN;
    }

    @Override
    public String description() {
        return "Replace the current task progress table. "
                + "Use for non-trivial multi-step execution progress, not durable project planning. "
                + "Keep steps short and actionable, with at most one in_progress step.";
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
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode stepItem = mapper.createObjectNode();
        stepItem.put("type", "object");
        ObjectNode stepProperties = mapper.createObjectNode();
        stepProperties.set("step", ToolSchemas.stringProperty(mapper, "Short progress step"));
        stepProperties.set("status", ToolSchemas.stringEnumProperty(mapper,
                "Step status", "pending", "in_progress", "completed"));
        stepItem.set("properties", stepProperties);
        ArrayNode required = mapper.createArrayNode();
        required.add("step");
        required.add("status");
        stepItem.set("required", required);

        ObjectNode properties = mapper.createObjectNode();
        properties.set("explanation", ToolSchemas.stringProperty(mapper,
                "Optional short explanation for this update"));
        properties.set("plan", ToolSchemas.arrayProperty(mapper,
                "Complete current progress table; replaces any previous progress table", stepItem));
        return ToolSchemas.objectSchema(mapper, properties, "plan");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        if (input == null || input.plan() == null) {
            return new ToolResult(name(), false, "plan array is required");
        }

        List<PlanStep> steps = new ArrayList<>();
        int inProgressCount = 0;
        for (PlanStepInput entry : input.plan()) {
            if (entry == null) {
                return new ToolResult(name(), false, "Each plan entry must be an object");
            }
            String step = entry.step() == null ? "" : entry.step().strip();
            if (step.isBlank()) {
                return new ToolResult(name(), false, "Each plan step must have non-empty step text");
            }
            PlanStepStatus status;
            try {
                status = PlanStepStatus.fromWire(entry.status());
            } catch (IllegalArgumentException exception) {
                return new ToolResult(name(), false, exception.getMessage());
            }
            if (status == PlanStepStatus.IN_PROGRESS) {
                inProgressCount++;
            }
            steps.add(new PlanStep(step, status));
        }
        if (inProgressCount > 1) {
            return new ToolResult(name(), false, "At most one plan step may be in_progress");
        }

        CurrentPlan plan = new CurrentPlan(steps);
        String explanation = input.explanation() == null ? "" : input.explanation().strip();
        context.session().updateCurrentPlan(plan);
        context.session().fireMetaEvent(new MetaEvent.PlanUpdated(plan, explanation));
        return new ToolResult(name(), true, "Plan updated");
    }
}
