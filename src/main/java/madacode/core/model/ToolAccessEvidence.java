package madacode.core.model;

import java.util.Objects;

/** Non-model audit evidence about filesystem paths a tool resolved during execution. */
public record ToolAccessEvidence(
        String path,
        EvidenceSource source,
        boolean heuristic) {

    public ToolAccessEvidence {
        path = Objects.requireNonNull(path, "path");
        source = Objects.requireNonNull(source, "source");
    }

    public enum EvidenceSource {
        RESOLVED_PATH
    }
}
