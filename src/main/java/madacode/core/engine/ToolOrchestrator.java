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
import madacode.core.session.Subscription;
import madacode.core.turn.CancellationException;
import madacode.core.turn.CancellationToken;
import madacode.core.turn.Turn;
import madacode.core.turn.TurnResult;
import madacode.core.turn.TurnRunner;

import madacode.tool.Tool;
import madacode.tool.ToolRegistry;
import madacode.tool.access.ToolAccessResolver;
import madacode.tool.access.ToolAccessScope;
import madacode.tool.validation.ToolInputCoercion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ToolOrchestrator {

    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final ToolAccessResolver toolAccessResolver;
    private final ObjectMapper mapper;

    public ToolOrchestrator(ToolRegistry toolRegistry, ToolExecutor toolExecutor) {
        this(toolRegistry, toolExecutor, ToolAccessResolver.defaultResolver(), new ObjectMapper());
    }

    public ToolOrchestrator(ToolRegistry toolRegistry, ToolExecutor toolExecutor, ObjectMapper mapper) {
        this(toolRegistry, toolExecutor, ToolAccessResolver.defaultResolver(), mapper);
    }

    public ToolOrchestrator(
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            ToolAccessResolver toolAccessResolver) {
        this(toolRegistry, toolExecutor, toolAccessResolver, new ObjectMapper());
    }

    public ToolOrchestrator(
            ToolRegistry toolRegistry,
            ToolExecutor toolExecutor,
            ToolAccessResolver toolAccessResolver,
            ObjectMapper mapper) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.toolAccessResolver = Objects.requireNonNull(toolAccessResolver, "toolAccessResolver");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public List<ToolResult> run(List<ToolCall> toolCalls, ToolUseContext context) {
        Objects.requireNonNull(toolCalls, "toolCalls");
        Objects.requireNonNull(context, "context");
        if (toolCalls.isEmpty()) return List.of();

        List<ResolvedToolCall> resolvedCalls = toolCalls.stream()
                .map((ToolCall call) -> resolve(call, context.toolAccessScope()))
                .toList();
        List<ToolResult> results = new ArrayList<>(Collections.nCopies(toolCalls.size(), null));
        int i = 0;
        while (i < toolCalls.size()) {
            int segmentStart = i;
            boolean safe = isConcurrencySafe(resolvedCalls.get(i));
            i++;
            while (i < toolCalls.size() && isConcurrencySafe(resolvedCalls.get(i)) == safe) i++;
            int segmentEnd = i;

            if (safe && segmentEnd - segmentStart > 1) {
                runConcurrentSegment(resolvedCalls, results, segmentStart, segmentEnd, context);
            } else {
                for (int k = segmentStart; k < segmentEnd; k++) {
                    if (context.cancellationToken().isCancelled()) {
                        i = k; // let post-segment cancellation handler fill from here
                        break;
                    }
                    results.set(k, toolExecutor.execute(resolvedCalls.get(k), context));
                }
            }
            if (context.cancellationToken().isCancelled() && i < toolCalls.size()) {
                String reason = reasonOrDefault(context);
                for (int k = i; k < toolCalls.size(); k++) {
                    results.set(k, errorResult(resolvedCalls.get(k).toolCall(),
                            "Cancelled before execution: " + reason, context));
                }
                return results;
            }
        }
        return results;
    }

    private void runConcurrentSegment(List<ResolvedToolCall> toolCalls,
                                      List<ToolResult> results,
                                      int start, int endExclusive,
                                      ToolUseContext context) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CancellationToken cancellationToken = context.cancellationToken();
        // Register a kill-hook AND withdraw it when the segment completes —
        // otherwise the callback (now pointing at a closed executor) would
        // linger on the cancellation token until the turn ends. Skip
        // registration for a non-cancellable token (e.g. NEVER): the hook
        // could never fire, so it would be dead weight, not a safety net.
        Subscription killSub = cancellationToken.isCancellable()
                ? cancellationToken.onCancel(executor::shutdownNow)
                : () -> {};
        try (killSub) {
            CompletionService<IndexedToolResult> completion = new ExecutorCompletionService<>(executor);
            int submitted = 0;
            for (int k = start; k < endExclusive; k++) {
                ResolvedToolCall call = toolCalls.get(k);
                final int slot = k;
                completion.submit(() -> {
                    try {
                        return new IndexedToolResult(slot, toolExecutor.execute(call, context));
                    } catch (Throwable t) {
                        if (t instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        String message = t instanceof CancellationException
                                ? "Cancelled: " + t.getMessage()
                                : "Tool execution failed: " + t.getMessage();
                        return new IndexedToolResult(slot, errorResult(call.toolCall(), message, context));
                    }
                });
                submitted++;
            }
            for (int idx = 0; idx < submitted; idx++) {
                try {
                    IndexedToolResult completed = completion.take().get();
                    results.set(completed.index(), completed.result());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    fillMissingConcurrentResults(toolCalls, results, start, endExclusive,
                            "Tool execution interrupted: " + e.getMessage(), context);
                    break;
                } catch (java.util.concurrent.CancellationException e) {
                    fillMissingConcurrentResults(toolCalls, results, start, endExclusive,
                            "Cancelled: " + reasonOrDefault(context), context);
                    break;
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    fillMissingConcurrentResults(toolCalls, results, start, endExclusive,
                            "Tool execution failed: " + cause.getMessage(), context);
                    break;
                }
            }
        } finally {
            executor.close();
        }
        // killSub auto-closes here via try-with-resources.
    }

    private static void fillMissingConcurrentResults(List<ResolvedToolCall> toolCalls,
                                                     List<ToolResult> results,
                                                     int start, int endExclusive,
                                                     String message,
                                                     ToolUseContext context) {
        for (int slot = start; slot < endExclusive; slot++) {
            if (results.get(slot) == null) {
                results.set(slot, errorResult(toolCalls.get(slot).toolCall(), message, context));
            }
        }
    }

    private static String reasonOrDefault(ToolUseContext context) {
        String r = context.cancellationToken().reason();
        return r == null ? "interrupted" : r;
    }

    private ResolvedToolCall resolve(ToolCall call, ToolAccessScope accessScope) {
        return ResolvedToolCall.resolve(call, toolRegistry, mapper, toolAccessResolver, accessScope);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean isConcurrencySafe(ResolvedToolCall call) {
        Tool<?> tool = call.tool();
        if (tool == null || !call.hasReusableTypedInput(call.toolCall().input())) return false;
        try {
            return ((Tool) tool).isConcurrencySafe(call.typedInput());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static ToolResult errorResult(ToolCall call, String message, ToolUseContext context) {
        ToolResult result = new ToolResult(call.toolName(), false, message);
        context.session().fireToolResultAvailable(call.id(), false, message);
        context.session().fireToolExecutionCompleted(call.id(), false, 0);
        return result;
    }

    private record IndexedToolResult(int index, ToolResult result) {}

    record ResolvedToolCall(
            ToolCall toolCall,
            Tool<?> tool,
            ObjectNode inputSnapshot,
            Object typedInput,
            ToolInputCoercion.ToolInputCoercionException coercionFailure,
            boolean resolved) {

        static ResolvedToolCall unresolved(ToolCall toolCall) {
            return new ResolvedToolCall(toolCall, null, null, null, null, false);
        }

        static ResolvedToolCall resolve(ToolCall toolCall, ToolRegistry toolRegistry, ObjectMapper mapper) {
            return resolve(toolCall, toolRegistry, mapper, null, null);
        }

        static ResolvedToolCall resolve(
                ToolCall toolCall,
                ToolRegistry toolRegistry,
                ObjectMapper mapper,
                ToolAccessResolver accessResolver,
                ToolAccessScope accessScope) {
            Tool<?> tool = toolRegistry.find(toolCall.toolName()).orElse(null);
            if (tool == null) {
                return new ResolvedToolCall(toolCall, null, toolCall.input().deepCopy(), null, null, true);
            }
            // Pre-resolution only avoids coercing input and marking concurrency-safe for
            // tools that cannot execute in this request. ToolExecutor repeats the same
            // access check as the authoritative execution boundary.
            if (accessResolver != null && accessResolver.exposedToolDenialReason(tool, accessScope) != null) {
                return new ResolvedToolCall(toolCall, tool, toolCall.input().deepCopy(), null, null, true);
            }
            try {
                Object typedInput = ToolInputCoercion.coerceUnchecked(tool, toolCall.input(), mapper);
                return new ResolvedToolCall(
                        toolCall, tool, toolCall.input().deepCopy(), typedInput, null, true);
            } catch (ToolInputCoercion.ToolInputCoercionException e) {
                return new ResolvedToolCall(
                        toolCall, tool, toolCall.input().deepCopy(), null, e, true);
            }
        }

        ResolvedToolCall resolveIfNeeded(
                ToolRegistry toolRegistry,
                ObjectMapper mapper,
                ToolAccessResolver accessResolver,
                ToolAccessScope accessScope) {
            return resolved ? this : resolve(toolCall, toolRegistry, mapper, accessResolver, accessScope);
        }

        ResolvedToolCall resolveIfNeeded(ToolRegistry toolRegistry, ObjectMapper mapper) {
            return resolveIfNeeded(toolRegistry, mapper, null, null);
        }

        boolean hasReusableTypedInput(ObjectNode effectiveInput) {
            return tool != null
                    && coercionFailure == null
                    && inputSnapshot != null
                    && inputSnapshot.equals(effectiveInput);
        }

        boolean hasReusableCoercionFailure(ObjectNode effectiveInput) {
            return tool != null
                    && coercionFailure != null
                    && inputSnapshot != null
                    && inputSnapshot.equals(effectiveInput);
        }
    }
}
