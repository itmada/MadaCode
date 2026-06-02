package madacode.tool;

import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;
import madacode.core.session.ConversationSession;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Objects;

public final class LongRunStageUpdateTool implements Tool<LongRunStageUpdateTool.Input> {

    public record Input(String intent, String confidence, String summary) {}

    @Override
    public String name() {
        return "longrun_stage_update";
    }

    @Override
    public String description() {
        return "Record a structured long-running workflow stage transition suggestion "
                + "for the current session. Use only in long-running planning or approval stages.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return false;
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("intent", ToolSchemas.stringEnumProperty(mapper,
                "Structured long-running user intent",
                "FINALIZE_PLAN", "APPROVE_EXECUTION", "CANCEL"));
        properties.set("confidence", ToolSchemas.stringEnumProperty(mapper,
                "Confidence in the inferred intent",
                "high", "medium", "low"));
        ObjectNode summary = ToolSchemas.stringProperty(mapper,
                "Short explanation of the user's explicit signal and what should happen next");
        summary.put("minLength", 1);
        properties.set("summary", summary);
        return ToolSchemas.objectSchema(mapper, properties, "intent", "confidence", "summary");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        Objects.requireNonNull(input, "input");
        ConversationSession session = context.session();
        if (!session.isLongRunningModeActive()) {
            return new ToolResult(name(), false,
                    "Long-running mode is not active for this session.");
        }

        var stage = session.longRunningStage();
        if (stage == null) {
            return new ToolResult(name(), false,
                    "Long-running mode is active but the current stage is unavailable.");
        }

        var intent = ConversationSession.LongRunningStageUpdateIntent.fromWire(input.intent()).orElse(null);
        if (intent == null) {
            return new ToolResult(name(), false,
                    "Invalid long-running intent: " + safe(input.intent()));
        }

        var confidence = ConversationSession.LongRunningConfidence.fromWire(input.confidence()).orElse(null);
        if (confidence == null) {
            return new ToolResult(name(), false,
                    "Invalid long-running confidence: " + safe(input.confidence()));
        }

        String summary = input.summary() == null ? "" : input.summary().strip();
        if (summary.isBlank()) {
            return new ToolResult(name(), false,
                    "summary must be non-empty");
        }

        if (!stage.allowsIntent(intent)) {
            return new ToolResult(name(), false,
                    "Intent " + intent + " is not allowed while long-running stage is " + stage + ".");
        }

        ConversationSession.LongRunningStageUpdate update =
                new ConversationSession.LongRunningStageUpdate(
                        stage, intent, confidence, summary, Instant.now());
        session.recordLongRunningStageUpdate(update);

        boolean readyForHandler = confidence == ConversationSession.LongRunningConfidence.HIGH;
        String output = """
                Long-running stage update recorded.
                stage: %s
                intent: %s
                confidence: %s
                ready_for_transition: %s
                summary: %s
                note: %s
                """.formatted(
                stage.name(),
                intent.name(),
                confidence.wireValue(),
                readyForHandler,
                summary,
                readyForHandler
                        ? "Handler may validate and apply this transition."
                        : "Keep discussing or ask for explicit confirmation before transitioning.");
        return new ToolResult(name(), true, output.stripTrailing());
    }

    private static String safe(String value) {
        return value == null ? "(missing)" : value;
    }
}
