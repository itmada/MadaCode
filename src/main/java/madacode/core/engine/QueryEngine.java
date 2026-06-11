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


import madacode.services.api.ApiClient;
import madacode.services.api.ApiClientException;
import madacode.services.compact.CompactPlanner;
import madacode.hook.HookManager;
import madacode.prompt.SystemPromptBuilder;
import madacode.logging.DefaultDiagnosticEvents;
import madacode.logging.DiagnosticEvents;
import madacode.permission.PermissionGate;
import madacode.tool.ToolRegistry;
import madacode.tool.validation.ToolInputValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class QueryEngine {

    private final ApiClient apiClient;
    private final ToolRegistry toolRegistry;
    private final SystemPromptBuilder systemPromptBuilder;
    private final PermissionGate permissionGate;
    private final ToolInputValidator toolInputValidator;
    private final CompactPlanner compactPlanner;
    private final Integer maxIterations;
    private final HookManager hookManager;
    private final DiagnosticEvents diagnosticEvents;
    private QueryEngine(Builder builder) {
        this.apiClient = Objects.requireNonNull(builder.apiClient, "apiClient");
        this.toolRegistry = Objects.requireNonNull(builder.toolRegistry, "toolRegistry");
        this.systemPromptBuilder = Objects.requireNonNull(builder.systemPromptBuilder, "systemPromptBuilder");
        this.permissionGate = Objects.requireNonNull(builder.permissionGate, "permissionGate");
        this.toolInputValidator = Objects.requireNonNull(builder.toolInputValidator, "toolInputValidator");
        this.compactPlanner = builder.compactPlanner;
        this.maxIterations = builder.maxIterations;
        this.hookManager = builder.hookManager;
        this.diagnosticEvents = Objects.requireNonNull(builder.diagnosticEvents, "diagnosticEvents");
    }

    public QueryEngine(ApiClient apiClient,
                       ToolRegistry toolRegistry,
                       SystemPromptBuilder systemPromptBuilder,
                       PermissionGate permissionGate) {
        this(new Builder(apiClient, toolRegistry, systemPromptBuilder, permissionGate));
    }

    public QueryEngine(ApiClient apiClient,
                       ToolRegistry toolRegistry,
                       SystemPromptBuilder systemPromptBuilder,
                       PermissionGate permissionGate,
                       DiagnosticEvents diagnosticEvents) {
        this(new Builder(apiClient, toolRegistry, systemPromptBuilder, permissionGate)
                .diagnosticEvents(diagnosticEvents));
    }

    /** Returns the API client used by this engine. */
    public ApiClient apiClient() { return apiClient; }

    /** Returns the tool registry used by this engine. */
    public ToolRegistry toolRegistry() { return toolRegistry; }

    public static Builder builder(ApiClient apiClient,
                                  ToolRegistry toolRegistry,
                                  SystemPromptBuilder systemPromptBuilder,
                                  PermissionGate permissionGate) {
        return new Builder(apiClient, toolRegistry, systemPromptBuilder, permissionGate);
    }

    public static final class Builder {
        private final ApiClient apiClient;
        private final ToolRegistry toolRegistry;
        private final SystemPromptBuilder systemPromptBuilder;
        private final PermissionGate permissionGate;
        private ToolInputValidator toolInputValidator = new ToolInputValidator();
        private CompactPlanner compactPlanner;
        private Integer maxIterations;
        private HookManager hookManager;
        private DiagnosticEvents diagnosticEvents = new DefaultDiagnosticEvents();

        Builder(ApiClient apiClient, ToolRegistry toolRegistry,
                SystemPromptBuilder systemPromptBuilder, PermissionGate permissionGate) {
            this.apiClient = apiClient;
            this.toolRegistry = toolRegistry;
            this.systemPromptBuilder = systemPromptBuilder;
            this.permissionGate = permissionGate;
        }

        public Builder maxIterations(int maxIterations) {
            if (maxIterations <= 0) {
                throw new IllegalArgumentException("maxIterations must be positive, was " + maxIterations);
            }
            this.maxIterations = maxIterations;
            return this;
        }
        public Builder unlimitedIterations()                { this.maxIterations = null; return this; }
        public Builder toolInputValidator(ToolInputValidator v) { this.toolInputValidator = Objects.requireNonNull(v); return this; }
        public Builder compactPlanner(CompactPlanner p)     { this.compactPlanner = p; return this; }
        public Builder hookManager(HookManager h)           { this.hookManager = h; return this; }
        public Builder diagnosticEvents(DiagnosticEvents d) { this.diagnosticEvents = Objects.requireNonNull(d); return this; }

        public QueryEngine build() { return new QueryEngine(this); }
    }

    // ---- Public API ------------------------------------------------------

    public TurnResult runTurn(ConversationSession session, String userInput) {
        return runTurn(session, userInput,
                new ToolUseContext(session.workingDirectory(), session));
    }

    public TurnResult runTurn(ConversationSession session, String userInput,
                              ToolUseContext ctx) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(userInput, "userInput");
        Objects.requireNonNull(ctx, "ctx");

        long turnStart = System.nanoTime();
        session.addMessage(Message.user(userInput));
        diagnosticEvents.turnStarted(session, maxIterations);

        ToolExecutor toolExecutor = new ToolExecutor(
                toolRegistry, toolInputValidator, permissionGate, hookManager, diagnosticEvents);
        ToolOrchestrator toolOrchestrator = new ToolOrchestrator(toolRegistry, toolExecutor);

        CancellationToken cancel = ctx.cancellationToken();

        int iteration = 0;
        while (maxIterations == null || iteration < maxIterations) {
            if (cancel.isCancelled()) {
                return completeWithCancellation(session, cancel.reason(), iteration, elapsedMs(turnStart));
            }
            if (compactPlanner != null) {
                compactPlanner.planAndApply(session, session::fireMetaEvent, cancel);
                // Compact may take seconds (model API call). If the user cancelled
                // during it, honor it now rather than firing a doomed turn request.
                if (cancel.isCancelled()) {
                    return completeWithCancellation(session, cancel.reason(),
                            iteration, elapsedMs(turnStart));
                }
            }
            long iterStart = System.nanoTime();

            // Recalculate visible tools and system prompt each iteration so that
            // stage changes within a turn (e.g. longrun_task_update completing a
            // task) are reflected in subsequent model requests.
            var visibleTools = SystemPromptBuilder.visibleToolsForSession(toolRegistry.tools(), session);
            String systemPrompt = systemPromptBuilder.build(
                    visibleTools, session.workingDirectory(), session);
            ToolUseContext executionCtx = ctx.withExposedTools(visibleTools);

            ApiClient.ApiResponse response;
            try (AssistantTurnWriter writer = AssistantTurnWriter.open(session)) {
                try {
                    session.fireMetaEvent(new MetaEvent.ModelRequestStarted());
                    response = apiClient.send(session.messages(), systemPrompt,
                            visibleTools, writer.sink(), cancel);
                    long iterElapsed = elapsedMs(iterStart);
                    diagnosticEvents.modelIterationCompleted(
                            session, iteration + 1, iterElapsed,
                            response.toolCalls() == null ? 0 : response.toolCalls().size());
                } catch (ApiClientException e) {
                    writer.abandon();
                    if (cancel.isCancelled()) {
                        return completeWithCancellation(session, cancel.reason(), iteration + 1, elapsedMs(turnStart));
                    }
                    return completeWithApiError(session, "Model request failed: " + e.getMessage(),
                            iteration + 1, elapsedMs(turnStart));
                } catch (CancellationException e) {
                    writer.abandon();
                    return completeWithCancellation(session, e.getMessage(), iteration + 1, elapsedMs(turnStart));
                } catch (RuntimeException e) {
                    writer.abandon();
                    if (cancel.isCancelled()) {
                        return completeWithCancellation(session, cancel.reason(), iteration + 1, elapsedMs(turnStart));
                    }
                    return completeWithApiError(session, "Unexpected model request failure: " + e.getMessage(),
                            iteration + 1, elapsedMs(turnStart));
                }
                writer.commit();
            }

            List<ToolCall> toolCalls = response.toolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                FinishReason finishReason = response.stopReason() == StopReason.MAX_TOKENS_REACHED
                        ? FinishReason.MODEL_TRUNCATED
                        : FinishReason.COMPLETED;
                diagnosticEvents.turnCompleted(session, finishReason,
                        iteration + 1, elapsedMs(turnStart));
                return new TurnResult(response.assistantText(), finishReason, iteration + 1);
            }

            List<ToolResult> results = toolOrchestrator.run(toolCalls, executionCtx);
            List<ContentBlock> toolResultBlocks = new ArrayList<>(results.size());
            for (int j = 0; j < toolCalls.size(); j++) {
                ToolResult result = results.get(j);
                toolResultBlocks.add(new ContentBlock.ToolResultBlock(
                        toolCalls.get(j).id(), result.output(), result.success(), -1));
            }
            session.addMessage(Message.user(toolResultBlocks));
            session.flushPendingControllerEvents();

            if (cancel.isCancelled()) {
                return completeWithCancellation(session, cancel.reason(),
                        iteration + 1, elapsedMs(turnStart));
            }
            iteration++;
        }

        String warning = "(Reached max iterations: " + maxIterations + ")";
        session.addMessage(Message.system(warning));
        session.fireMetaEvent(new MetaEvent.Error(warning, FinishReason.MAX_ITERATIONS));
        diagnosticEvents.turnCompleted(session, FinishReason.MAX_ITERATIONS,
                maxIterations, elapsedMs(turnStart));
        return new TurnResult(warning, FinishReason.MAX_ITERATIONS, maxIterations);
    }

    // ---- Private helpers -------------------------------------------------

    private TurnResult completeWithApiError(ConversationSession session, String message,
                                            int iterations, long durationMs) {
        return completeTerminal(session, TerminalOutcome.apiError(message),
                iterations, durationMs);
    }

    private TurnResult completeWithCancellation(ConversationSession session, String reason,
                                                int iterations, long durationMs) {
        return completeTerminal(session, TerminalOutcome.cancellation(reason),
                iterations, durationMs);
    }

    private TurnResult completeTerminal(ConversationSession session, TerminalOutcome outcome,
                                        int iterations, long durationMs) {
        session.addMessage(Message.assistantTerminal(outcome.message(), outcome.finishReason()));
        if (outcome.fireErrorMetaEvent()) {
            session.fireMetaEvent(new MetaEvent.Error(outcome.message(), outcome.finishReason()));
        }
        diagnosticEvents.turnCompleted(session, outcome.finishReason(), iterations, durationMs);
        return new TurnResult(outcome.message(), outcome.finishReason(), iterations);
    }

    private record TerminalOutcome(String message,
                                   FinishReason finishReason,
                                   boolean fireErrorMetaEvent) {
        private static TerminalOutcome apiError(String message) {
            return new TerminalOutcome(message, FinishReason.API_ERROR, true);
        }

        private static TerminalOutcome cancellation(String reason) {
            boolean fromPermission = CancellationToken.REASON_PERMISSION_DENIED.equals(reason);
            FinishReason finishReason = fromPermission
                    ? FinishReason.PERMISSION_CANCELLED
                    : FinishReason.CANCELLED;
            String message = "(Cancelled" + (reason == null ? "" : ": " + reason) + ")";
            return new TerminalOutcome(message, finishReason, !fromPermission);
        }
    }

    private long elapsedMs(long startedAtNanos) {
        return java.time.Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }
}
