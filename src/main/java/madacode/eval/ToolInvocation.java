package madacode.eval;

import madacode.core.model.ToolAccessEvidence;

import java.util.List;
import java.util.Objects;

/** A tool request/result pair observed during an eval attempt. */
public record ToolInvocation(
        String name,
        String inputJson,
        String resultJson,
        List<ToolAccessEvidence> accessEvidence,
        Phase phase,
        int ordinal) {

    public ToolInvocation(
            String name,
            String inputJson,
            String resultJson,
            Phase phase,
            int ordinal) {
        this(name, inputJson, resultJson, List.of(), phase, ordinal);
    }

    public ToolInvocation {
        name = Objects.requireNonNull(name, "name");
        inputJson = inputJson == null ? "{}" : inputJson;
        resultJson = resultJson == null ? "" : resultJson;
        accessEvidence = accessEvidence == null ? List.of() : List.copyOf(accessEvidence);
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
