package madacode.eval;

import java.util.Objects;

/** A tool request/result pair observed during an eval attempt. */
public record ToolInvocation(
        String name,
        String inputJson,
        String resultJson,
        Phase phase,
        int ordinal) {

    public ToolInvocation {
        name = Objects.requireNonNull(name, "name");
        inputJson = inputJson == null ? "{}" : inputJson;
        resultJson = resultJson == null ? "" : resultJson;
        phase = Objects.requireNonNull(phase, "phase");
        if (ordinal < 0) {
            throw new IllegalArgumentException("tool invocation ordinal must not be negative");
        }
    }

    public enum Phase {
        CONTROL,
        WORKER,
        SUBAGENT
    }
}
