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
import madacode.logging.DefaultDiagnosticEvents;
import madacode.logging.DiagnosticEvents;
import madacode.permission.PermissionDecision;
import madacode.permission.PermissionGate;
import madacode.tool.Tool;
import madacode.tool.ToolRegistry;
import madacode.tool.access.ToolAccessResolver;
import madacode.tool.validation.ToolInputCoercion;
import madacode.tool.validation.ToolInputValidator;
import madacode.tool.validation.ValidationResult;

import java.time.Duration;
import java.util.Objects;

public final class ToolExecutor {

    /** Thread-local toolUseId for the currently-executing tool, used by permission rendering. */
    public static final ThreadLocal<String> CURRENT_TOOL_USE_ID = new ThreadLocal<>();

    private final ToolRegistry toolRegistry;
    private final ToolInputValidator inputValidator;
    private final PermissionGate permissionGate;
    private final HookManager hookManager;
    private final DiagnosticEvents diagnosticEvents;
    private final ToolAccessResolver toolAccessResolver;
    private final ObjectMapper mapper;

    public ToolExecutor(ToolRegistry toolRegistry,
                        ToolInputValidator inputValidator,
                        PermissionGate permissionGate,
                        HookManager hookManager) {
        this(toolRegistry, inputValidator, permissionGate, hookManager, new DefaultDiagnosticEvents());
    }

    public ToolExecutor(ToolRegistry toolRegistry,
                        ToolInputValidator inputValidator,
                        PermissionGate permissionGate,
                        HookManager hookManager,
                        DiagnosticEvents diagnosticEvents) {
        this(toolRegistry, inputValidator, permissionGate, hookManager,
                diagnosticEvents, ToolAccessResolver.defaultResolver());
    }

    public ToolExecutor(ToolRegistry toolRegistry,
                        ToolInputValidator inputValidator,
                        PermissionGate permissionGate,
                        HookManager hookManager,
                        ObjectMapper mapper) {
        this(toolRegistry, inputValidator, permissionGate, hookManager,
                new DefaultDiagnosticEvents(), ToolAccessResolver.defaultResolver(), mapper);
    }

    public ToolExecutor(ToolRegistry toolRegistry,
                        ToolInputValidator inputValidator,
                        PermissionGate permissionGate,
                        HookManager hookManager,
                        DiagnosticEvents diagnosticEvents,
                        ToolAccessResolver toolAccessResolver) {
        this(toolRegistry, inputValidator, permissionGate, hookManager,
                diagnosticEvents, toolAccessResolver, new ObjectMapper());
    }

    public ToolExecutor(ToolRegistry toolRegistry,
                        ToolInputValidator inputValidator,
                        PermissionGate permissionGate,
                        HookManager hookManager,
                        DiagnosticEvents diagnosticEvents,
                        ToolAccessResolver toolAccessResolver,
                        ObjectMapper mapper) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.inputValidator = Objects.requireNonNull(inputValidator, "inputValidator");
        this.permissionGate = Objects.requireNonNull(permissionGate, "permissionGate");
        this.hookManager = hookManager;
        this.diagnosticEvents = Objects.requireNonNull(diagnosticEvents, "diagnosticEvents");
        this.toolAccessResolver = Objects.requireNonNull(toolAccessResolver, "toolAccessResolver");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public ToolResult execute(ToolCall toolCall, ToolUseContext context) {
        return execute(ToolOrchestrator.ResolvedToolCall.unresolved(toolCall), context);
    }

    ToolResult execute(ToolOrchestrator.ResolvedToolCall resolvedCall, ToolUseContext context) {
        Objects.requireNonNull(resolvedCall, "resolvedCall");
        ToolCall toolCall = Objects.requireNonNull(resolvedCall.toolCall(), "toolCall");
        Objects.requireNonNull(toolCall, "toolCall");
        Objects.requireNonNull(context, "context");
        ConversationSession session = context.session();

        if (context.cancellationToken().isCancelled()) {
            return failResult(
                    session,
                    toolCall,
                    toolCall.toolName(),
                    toolCall.input(),
                    ToolOrchestrator.skippedBeforeExecutionMessage(context.cancellationToken().reason()),
                    0,
                    false);
        }

        resolvedCall = resolvedCall.resolveIfNeeded(
                toolRegistry, mapper, toolAccessResolver, context.toolAccessScope());
        Tool<?> tool = resolvedCall.tool();
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

        // Authoritative access boundary. QueryEngine binds each tool batch to the
        // exact declarations sent with the model request, so tools loaded after
        // that request cannot be called until the next iteration. ToolOrchestrator
        // may pre-check this only to skip coercion/concurrency classification.
        String denialReason = toolAccessResolver.exposedToolDenialReason(tool, context.toolAccessScope());
        if (denialReason != null) {
            return failResult(session, toolCall, tool.name(), toolCall.input(), denialReason, 0, false);
        }

        CURRENT_TOOL_USE_ID.set(toolCall.id());
        ObjectNode effectiveInput = toolCall.input();
        if (hookManager != null && !tool.bypassesHooks()) {
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
            diagnosticEvents.toolValidationFailed(session, tool.name(), validation.errors());
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

        if (session.isPlanMode() && !tool.isPlanModeSafe()) {
            return failResult(
                    session,
                    toolCall,
                    tool.name(),
                    effectiveInput,
                    "Plan mode active — only read tools are allowed. "
                            + "The host must exit plan mode before implementation tools are available.",
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
        if (resolvedCall.hasReusableTypedInput(effectiveInput)) {
            typedInput = resolvedCall.typedInput();
        } else if (resolvedCall.hasReusableCoercionFailure(effectiveInput)) {
            long durationMs = elapsedMs(toolStart);
            return failResult(
                    session,
                    toolCall,
                    tool.name(),
                    effectiveInput,
                    "Invalid tool input for " + tool.name() + ": " + resolvedCall.coercionFailure().getMessage(),
                    durationMs,
                    true);
        } else {
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
        }
        try {
            @SuppressWarnings({"unchecked", "rawtypes"})
            ToolResult result = ((Tool) tool).execute(typedInput, context);
            long durationMs = elapsedMs(toolStart);
            diagnosticEvents.toolExecutionCompleted(session, tool.name(), result.success(), durationMs);
            emitCompleted(session, toolCall.id(), tool.name(), effectiveInput, result, durationMs);
            if (hookManager != null && !tool.bypassesHooks()) {
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

    private ToolResult failResult(ConversationSession session,
                                  ToolCall toolCall,
                                  String toolName,
                                  ObjectNode input,
                                  String message,
                                  long durationMs,
                                  boolean logExecutionCompleted) {
        ToolResult result = new ToolResult(toolName, false, message);
        if (logExecutionCompleted) {
            diagnosticEvents.toolExecutionCompleted(session, toolName, false, durationMs);
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
