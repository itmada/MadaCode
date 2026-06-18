package madacode.eval;

import java.util.List;

/** Immutable evidence collected across every session participating in one attempt. */
public record ExecutionTrace(
        List<ToolInvocation> invocations,
        List<TouchedFile> fileEffects,
        List<String> userTurns,
        List<String> assistantTurns,
        String finalText,
        RunMetrics metrics) {

    public ExecutionTrace {
        invocations = invocations == null ? List.of() : List.copyOf(invocations);
        fileEffects = fileEffects == null ? List.of() : List.copyOf(fileEffects);
        userTurns = userTurns == null ? List.of() : List.copyOf(userTurns);
        assistantTurns = assistantTurns == null ? List.of() : List.copyOf(assistantTurns);
        finalText = finalText == null ? "" : finalText;
        metrics = metrics == null ? RunMetrics.ZERO : metrics;
    }
}
