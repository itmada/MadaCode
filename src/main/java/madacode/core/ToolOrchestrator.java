package madacode.core;

import madacode.tool.Tool;
import madacode.tool.ToolInputCoercion;
import madacode.tool.ToolRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class ToolOrchestrator {

    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper mapper;

    public ToolOrchestrator(ToolRegistry toolRegistry, ToolExecutor toolExecutor) {
        this(toolRegistry, toolExecutor, new ObjectMapper());
    }

    public ToolOrchestrator(ToolRegistry toolRegistry, ToolExecutor toolExecutor, ObjectMapper mapper) {
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.toolExecutor = Objects.requireNonNull(toolExecutor, "toolExecutor");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public List<ToolResult> run(List<ToolCall> toolCalls, ToolUseContext context) {
        Objects.requireNonNull(toolCalls, "toolCalls");
        Objects.requireNonNull(context, "context");
        if (toolCalls.isEmpty()) return List.of();

        List<ToolResult> results = new ArrayList<>(Collections.nCopies(toolCalls.size(), null));
        int i = 0;
        while (i < toolCalls.size()) {
            int segmentStart = i;
            boolean safe = isConcurrencySafe(toolCalls.get(i));
            i++;
            while (i < toolCalls.size() && isConcurrencySafe(toolCalls.get(i)) == safe) i++;
            int segmentEnd = i;

            if (safe && segmentEnd - segmentStart > 1) {
                runConcurrentSegment(toolCalls, results, segmentStart, segmentEnd, context);
            } else {
                for (int k = segmentStart; k < segmentEnd; k++) {
                    if (context.cancellationToken().isCancelled()) {
                        i = k; // let post-segment cancellation handler fill from here
                        break;
                    }
                    results.set(k, toolExecutor.execute(toolCalls.get(k), context));
                }
            }
            if (context.cancellationToken().isCancelled() && i < toolCalls.size()) {
                String reason = reasonOrDefault(context);
                for (int k = i; k < toolCalls.size(); k++) {
                    results.set(k, errorResult(toolCalls.get(k),
                            "Cancelled before execution: " + reason, context));
                }
                return results;
            }
        }
        return results;
    }

    private void runConcurrentSegment(List<ToolCall> toolCalls,
                                      List<ToolResult> results,
                                      int start, int endExclusive,
                                      ToolUseContext context) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        // Register a kill-hook AND withdraw it when the segment completes —
        // otherwise the callback (now pointing at a closed executor) would
        // linger on the cancellation token until the turn ends.
        try (Subscription killSub = context.cancellationToken().onCancel(executor::shutdownNow)) {
            List<Future<ToolResult>> futures = new ArrayList<>(endExclusive - start);
            for (int k = start; k < endExclusive; k++) {
                ToolCall call = toolCalls.get(k);
                futures.add(executor.submit(() -> toolExecutor.execute(call, context)));
            }
            for (int idx = 0; idx < futures.size(); idx++) {
                int slot = start + idx;
                try {
                    results.set(slot, futures.get(idx).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.set(slot, errorResult(toolCalls.get(slot),
                            "Tool execution interrupted: " + e.getMessage(), context));
                } catch (java.util.concurrent.CancellationException e) {
                    results.set(slot, errorResult(toolCalls.get(slot),
                            "Cancelled: " + reasonOrDefault(context), context));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    if (cause instanceof CancellationException) {
                        results.set(slot, errorResult(toolCalls.get(slot),
                                "Cancelled: " + cause.getMessage(), context));
                    } else {
                        results.set(slot, errorResult(toolCalls.get(slot),
                                "Tool execution failed: " + cause.getMessage(), context));
                    }
                }
            }
        } finally {
            executor.close();
        }
        // killSub auto-closes here via try-with-resources.
    }

    private static String reasonOrDefault(ToolUseContext context) {
        String r = context.cancellationToken().reason();
        return r == null ? "interrupted" : r;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private boolean isConcurrencySafe(ToolCall call) {
        Tool<?> tool = toolRegistry.find(call.toolName()).orElse(null);
        if (tool == null) return false;
        try {
            Object typed = ToolInputCoercion.coerceUnchecked(tool, call.input(), mapper);
            return ((Tool) tool).isConcurrencySafe(typed);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static ToolResult errorResult(ToolCall call, String message, ToolUseContext context) {
        ToolResult result = new ToolResult(call.toolName(), false, message);
        context.session().fireToolExecutionCompleted(call.id(), false, 0);
        return result;
    }
}
