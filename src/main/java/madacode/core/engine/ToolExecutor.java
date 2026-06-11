package madacode.core.engine;

import madacode.core.model.ContentBlock;
import madacode.core.model.FinishReason;
import madacode.core.model.Message;
import madacode.core.model.MetaEvent;
import madacode.core.model.StopReason;
import madacode.core.model.ToolCall;
import madacode.core.model.ToolResult;
import madacode.core.session.AssistantTurnWriter;
import madacode.core.session.ConversationSession;
import madacode.core.turn.CancellationException;
import madacode.core.turn.CancellationToken;
import madacode.core.turn.Turn;
import madacode.core.turn.TurnResult;
import madacode.core.turn.TurnRunner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import madacode.hook.HookManager;
import madacode.logging.DiagnosticEventLogger;
import madacode.permission.PermissionDecision;
import madacode.permission.PermissionGate;
import madacode.tool.Tool;
import madacode.tool.ToolRegistry;
import madacode.tool.ToolVisibility;
import madacode.tool.validation.ToolInputCoercion;
import madacode.tool.validation.ToolInputValidator;
import madacode.tool.validation.ValidationResult;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

public final class ToolExecutor {

    /** Thread-local toolUseId for the currently-executing tool, used by permission rendering. */
    public static final ThreadLocal<String> CURRENT_TOOL_USE_ID = new ThreadLocal<>();

    private static final Set<String> PLAN_MODE_ALLOWED = Set.of(
            "enter_plan_mode", "exit_plan_mode", "plan_create", "plan_get", "plan_list",
            "plan_update", "todo_write", "skill", "ask_user_question", "agent",
            "tool_search", "longrun_plan_update", "longrun_state_transition_request",
            "longrun_task_update");

    private final ToolRegistry toolRegistry;
    private final ToolInputValidator inputValidator;
    private final PermissionGate permissionGate;
    private final HookManager hookManager;
    private final ObjectMapper mapper;

    public ToolExecutor(ToolRegistry toolRegistry,
                        ToolInputValidator inputValidator,
                        PermissionGate permissionGate,
                        HookManager hookManager) {
        this(toolRegistry, inputValidator, permissionGate, hookManager, new ObjectMapper());
    }

    public ToolExecutor(ToolRegistry toolRegistry,
                        ToolInputValidator inputValidator,
                        PermissionGate permissionGate,
                        HookManager hookManager,
                        ObjectMapper mapper) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.inputValidator = Objects.requireNonNull(inputValidator, "inputValidator");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate");
        this.hookManager = hookManager;
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public ToolResult execute(ToolCall toolCall, ToolUseContext context) {
        Objects.requireNonNull(toolCall, "toolCall");
        Objects.requireNonNull(context, "context");
        ConversationSession session = context.session();

        if (context.cancellationToken().isCancelled()) {
            return failResult(
                    session,
                    toolCall,
                    toolCall.toolName(),
                    toolCall.input(),
                    "Cancelled before execution: " + context.cancellationToken().reason(),
                    0,
                    false);
        }

        Tool<?> tool = toolRegistry.find(toolCall.toolName()).orElse(null);
        if (tool == null) {
            // An unknown tool is a per-tool, recoverable failure (the model can
            // retry with a correct name), not a turn-terminal error. Handle it
            // exactly like the other early-return error branches below: return a
            // failed result and finalize the card. We must NOT fire
            // MetaEvent.Error here — that is the turn-terminal signal (owned by
            // QueryEngine with a FinishReason) and would abort the whole turn,
            // clearing the cards of the other tools in this same batch and
            // leaving any subsequent permission prompt with no card to render.
            return failResult(
                    session,
                    toolCall,
                    toolCall.toolName(),
                    toolCall.input(),
                    "Error: unknown tool \"" + toolCall.toolName() + "\"",
                    0,
                    false);
        }

        // Visibility is a hard execution boundary. QueryEngine binds each tool
        // batch to the exact declarations sent with the model request, so tools
        // loaded after that request cannot be called until the next iteration.
        String denialReason = ToolVisibility.exposedToolDenialReason(tool, context);
        if (denialReason != null) {
            return failResult(session, toolCall, tool.name(), toolCall.input(), denialReason, 0, false);
        }

        CURRENT_TOOL_USE_ID.set(toolCall.id());
        ObjectNode effectiveInput = toolCall.input();
        if (hookManager != null && !tool.name().equals("agent")) {
            var preResult = hookManager.runPreToolUse(
                    tool.name(), effectiveInput, session.sessionId());
            if (!preResult.allowed()) {
                return failResult(
                        session,
                        toolCall,
                        tool.name(),
                        effectiveInput,
                        "Hook rejected: " + preResult.denialReason(),
                        0,
                        false);
            }
            if (preResult.effectiveInput() != null) {
                effectiveInput = preResult.effectiveInput();
            }
        }

        ValidationResult validation = inputValidator.validate(tool, effectiveInput);
        if (!validation.valid()) {
            DiagnosticEventLogger.toolValidationFailed(session, tool.name(), validation.errors());
            return failResult(
                    session,
                    toolCall,
                    tool.name(),
                    effectiveInput,
                    "Invalid tool input for " + tool.name() + ": "
                            + String.join("; ", validation.errors()),
                    0,
                    false);
        }

        if (session.isPlanMode() && !tool.isReadOnly() && !PLAN_MODE_ALLOWED.contains(tool.name())) {
            return failResult(
                    session,
                    toolCall,
                    tool.name(),
                    effectiveInput,
                    "Plan mode active — only read tools, ask_user_question, "
                            + "and task management tools are allowed. "
                            + "Use exit_plan_mode to leave plan mode.",
                    0,
                    false);
        }

        session.fireToolExecutionReached(toolCall.id(), tool.name(), effectiveInput);

        PermissionDecision decision = permissionGate.check(tool, effectiveInput, context);
        if (!decision.isAllowed()) {
            return failResult(
                    session,
                    toolCall,
                    tool.name(),
                    effectiveInput,
                    "Permission denied: " + decision.reason(),
                    0,
                    false);
        }

        session.fireToolExecutionStarted(toolCall.id(), tool.name(), effectiveInput);
        long toolStart = System.nanoTime();
        Object typedInput;
        try {
            typedInput = ToolInputCoercion.coerceUnchecked(tool, effectiveInput, mapper);
        } catch (ToolInputCoercion.ToolInputCoercionException e) {
            long durationMs = elapsedMs(toolStart);
            return failResult(
                    session,
                    toolCall,
                    tool.name(),
                    effectiveInput,
                    "Invalid tool input for " + tool.name() + ": " + e.getMessage(),
                    durationMs,
                    true);
        }
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            ToolResult result = ((Tool) tool).execute(typedInput, context);
            long durationMs = elapsedMs(toolStart);
            DiagnosticEventLogger.toolExecutionCompleted(session, tool.name(), result.success(), durationMs);
            emitCompleted(session, toolCall.id(), tool.name(), effectiveInput, result, durationMs);
            if (hookManager != null && !tool.name().equals("agent")) {
                hookManager.runPostToolUse(tool.name(), effectiveInput,
                        session.sessionId(), result.success(), result.output());
            }
            return result;
        } catch (CancellationException e) {
            long durationMs = elapsedMs(toolStart);
            return failResult(
                    session,
                    toolCall,
                    tool.name(),
                    effectiveInput,
                    "Cancelled: " + e.getMessage(),
                    durationMs,
                    true);
        } catch (Exception e) {
            if (context.cancellationToken().isCancelled()) {
                long durationMs = elapsedMs(toolStart);
                return failResult(
                        session,
                        toolCall,
                        tool.name(),
                        effectiveInput,
                        "Cancelled: " + context.cancellationToken().reason(),
                        durationMs,
                        true);
            }
            long durationMs = elapsedMs(toolStart);
            return failResult(
                    session,
                    toolCall,
                    tool.name(),
                    effectiveInput,
                    "Tool execution failed: " + e.getMessage(),
                    durationMs,
                    true);
        }
    }

    private static ToolResult failResult(ConversationSession session,
                                         ToolCall toolCall,
                                         String toolName,
                                         ObjectNode input,
                                         String message,
                                         long durationMs,
                                         boolean logExecutionCompleted) {
        ToolResult result = new ToolResult(toolName, false, message);
        if (logExecutionCompleted) {
            DiagnosticEventLogger.toolExecutionCompleted(session, toolName, false, durationMs);
        }
        emitCompleted(session, toolCall.id(), toolName, input, result, durationMs);
        return result;
    }

    private static void emitCompleted(ConversationSession session,
                                      String id, String toolName, ObjectNode input,
                                      ToolResult result, long durationMs) {
        CURRENT_TOOL_USE_ID.remove();
        session.fireToolResultAvailable(id, result.success(), result.output());
        session.fireToolExecutionCompleted(id, result.success(), durationMs);
    }

    private static long elapsedMs(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }
}
