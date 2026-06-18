package madacode.eval;

import java.util.Objects;

/** A workspace path whose final state differs from the attempt's initial snapshot. */
public record TouchedFile(String relPath, ChangeKind kind) {

    public TouchedFile {
        relPath = Objects.requireNonNull(relPath, "relPath");
        kind = Objects.requireNonNull(kind, "kind");
    }

    public enum ChangeKind {
        CREATED,
        MODIFIED,
        DELETED
    }
}
