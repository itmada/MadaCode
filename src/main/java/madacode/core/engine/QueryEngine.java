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

import madacode.core.session.SessionMode;
import madacode.services.api.ApiClient;
import madacode.services.api.ApiClientException;
import madacode.services.compact.CompactPlanner;
import madacode.hook.HookManager;
import madacode.prompt.SystemPromptBuilder;
import madacode.logging.DiagnosticEventLogger;
import madacode.permission.PermissionGate;
import madacode.tool.ToolRegistry;
import madacode.tool.validation.ToolInputValidator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class QueryEngine {

    public static final int DEFAULT_MAX_ITERATIONS = 15;

    private final ApiClient apiClient;
    private final ToolRegistry toolRegistry;
    private final SystemPromptBuilder systemPromptBuilder;
    private final PermissionGate permissionGate;
    private final ToolInputValidator toolInputValidator;
    private final CompactPlanner compactPlanner;
    private final int maxIterations;
    private final int maxToolCalls;
    private final HookManager hookManager;

    private QueryEngine(Builder builder) {
        this.apiClient = Objects.requireNonNull(builder.apiClient, "apiClient");
        this.toolRegistry = Objects.requireNonNull(builder.toolRegistry, "toolRegistry");
        this.systemPromptBuilder = Objects.requireNonNull(builder.systemPromptBuilder, "systemPromptBuilder");
        this.permissionGate = Objects.requireNonNull(builder.permissionGate, "permissionGate");
        this.toolInputValidator = Objects.requireNonNull(builder.toolInputValidator, "toolInputValidator");
        this.compactPlanner = builder.compactPlanner;
        this.maxIterations = builder.maxIterations;
        this.maxToolCalls = builder.maxToolCalls;
        this.hookManager = builder.hookManager;
    }

    public QueryEngine(ApiClient apiClient,
                       ToolRegistry toolRegistry,
                       SystemPromptBuilder systemPromptBuilder,
                       PermissionGate permissionGate) {
        this(new Builder(apiClient, toolRegistry, systemPromptBuilder, permissionGate));
    }

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
        private int maxIterations = DEFAULT_MAX_ITERATIONS;
        private int maxToolCalls = Integer.MAX_VALUE;
        private HookManager hookManager;

        Builder(ApiClient apiClient, ToolRegistry toolRegistry,
                SystemPromptBuilder systemPromptBuilder, PermissionGate permissionGate) {
            this.apiClient = apiClient;
            this.toolRegistry = toolRegistry;
            this.systemPromptBuilder = systemPromptBuilder;
            this.permissionGate = permissionGate;
        }

        public Builder maxIterations(int maxIterations)     { this.maxIterations = maxIterations; return this; }
        public Builder maxToolCalls(int maxToolCalls)       { this.maxToolCalls = maxToolCalls; return this; }
        public Builder toolInputValidator(ToolInputValidator v) { this.toolInputValidator = Objects.requireNonNull(v); return this; }
        public Builder compactPlanner(CompactPlanner p)     { this.compactPlanner = p; return this; }
        public Builder hookManager(HookManager h)           { this.hookManager = h; return this; }

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
        DiagnosticEventLogger.turnStarted(session, maxIterations);

        ToolExecutor toolExecutor = new ToolExecutor(
                toolRegistry, toolInputValidator, permissionGate, hookManager);
        ToolOrchestrator toolOrchestrator = new ToolOrchestrator(toolRegistry, toolExecutor);

        CancellationToken cancel = ctx.cancellationToken();
        int toolCallsUsed = 0;

        for (int iteration = 0; iteration < maxIterations; iteration++) {
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

            ApiClient.ApiResponse response;
            try (AssistantTurnWriter writer = AssistantTurnWriter.open(session)) {
                try {
                    session.fireMetaEvent(new MetaEvent.ModelRequestStarted());
                    response = apiClient.send(session.messages(), systemPrompt,
                            visibleTools, writer.sink(), cancel);
                    long iterElapsed = elapsedMs(iterStart);
                    DiagnosticEventLogger.modelIterationCompleted(
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
                DiagnosticEventLogger.turnCompleted(session, finishReason,
                        iteration + 1, elapsedMs(turnStart));
                return new TurnResult(response.assistantText(), finishReason, iteration + 1);
            }

            if (toolCallsUsed + toolCalls.size() > maxToolCalls) {
                String warning = "(Reached max tool calls: " + maxToolCalls + ")";
                session.addMessage(Message.user(skippedToolResultBlocks(toolCalls, warning)));
                session.fireMetaEvent(new MetaEvent.Error(warning, FinishReason.MAX_TOOL_CALLS));
                DiagnosticEventLogger.turnCompleted(session, FinishReason.MAX_TOOL_CALLS,
                        iteration + 1, elapsedMs(turnStart));
                return new TurnResult(warning, FinishReason.MAX_TOOL_CALLS, iteration + 1);
            }
            toolCallsUsed += toolCalls.size();

            List<ToolResult> results = toolOrchestrator.run(toolCalls, ctx);
            List<ContentBlock> toolResultBlocks = new ArrayList<>(results.size());
            for (int j = 0; j < toolCalls.size(); j++) {
                ToolResult result = results.get(j);
                toolResultBlocks.add(new ContentBlock.ToolResultBlock(
                        toolCalls.get(j).id(), result.output(), result.success(), -1));
            }
            session.addMessage(Message.user(toolResultBlocks));

            if (shouldStopAfterLongRunningStageUpdate(session)) {
                session.addMessage(Message.assistant(
                        "Long-running stage transition recorded. Waiting for harness to apply it."));
                DiagnosticEventLogger.turnCompleted(session, FinishReason.COMPLETED,
                        iteration + 1, elapsedMs(turnStart));
                return new TurnResult("Long-running stage transition recorded.",
                        FinishReason.COMPLETED, iteration + 1);
            }

            if (cancel.isCancelled()) {
                return completeWithCancellation(session, cancel.reason(),
                        iteration + 1, elapsedMs(turnStart));
            }
        }

        String warning = "(Reached max iterations: " + maxIterations + ")";
        session.addMessage(Message.system(warning));
        session.fireMetaEvent(new MetaEvent.Error(warning, FinishReason.MAX_ITERATIONS));
        DiagnosticEventLogger.turnCompleted(session, FinishReason.MAX_ITERATIONS,
                maxIterations, elapsedMs(turnStart));
        return new TurnResult(warning, FinishReason.MAX_ITERATIONS, maxIterations);
    }

    // ---- Private helpers -------------------------------------------------

    private TurnResult completeWithApiError(ConversationSession session, String message,
                                            int iterations, long durationMs) {
        session.addMessage(Message.assistant(message));
        session.fireMetaEvent(new MetaEvent.Error(message, FinishReason.API_ERROR));
        DiagnosticEventLogger.turnCompleted(session, FinishReason.API_ERROR, iterations, durationMs);
        return new TurnResult(message, FinishReason.API_ERROR, iterations);
    }

    private TurnResult completeWithCancellation(ConversationSession session, String reason,
                                                int iterations, long durationMs) {
        boolean fromPermission = CancellationToken.REASON_PERMISSION_DENIED.equals(reason);
        FinishReason finishReason = fromPermission
                ? FinishReason.PERMISSION_CANCELLED
                : FinishReason.CANCELLED;
        String message = "(Cancelled" + (reason == null ? "" : ": " + reason) + ")";
        session.addMessage(Message.assistant(message));
        if (!fromPermission) {
            session.fireMetaEvent(new MetaEvent.Error(message, finishReason));
        }
        DiagnosticEventLogger.turnCompleted(session, finishReason, iterations, durationMs);
        return new TurnResult(message, finishReason, iterations);
    }

    private boolean shouldStopAfterLongRunningStageUpdate(ConversationSession session) {
        if (session.workflowMode() != SessionMode.LONG_RUNNING) return false;
        var update = session.lastLongRunningStageUpdate().orElse(null);
        if (update == null) return false;
        if (update.confidence() != ConversationSession.LongRunningConfidence.HIGH) return false;
        return session.longRunningStage() == update.stage();
    }

    private long elapsedMs(long startedAtNanos) {
        return java.time.Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
    }

    private List<ContentBlock> skippedToolResultBlocks(List<ToolCall> toolCalls, String warning) {
        List<ContentBlock> blocks = new ArrayList<>(toolCalls.size());
        for (ToolCall toolCall : toolCalls) {
            blocks.add(new ContentBlock.ToolResultBlock(
                    toolCall.id(),
                    "Tool call skipped: max tool calls reached. " + warning,
                    false,
                    -1));
        }
        return blocks;
    }
}
