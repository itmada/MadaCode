package madacode.eval;

import java.util.List;

/** Relative paths and non-fatal write warnings for one attempt's debug artifacts. */
public record AttemptArtifacts(
        String directory,
        List<String> files,
        List<String> warnings) {

    public static final AttemptArtifacts NONE = new AttemptArtifacts(null, List.of(), List.of());

    public AttemptArtifacts {
        files = files == null ? List.of() : List.copyOf(files);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public boolean present() {
        return directory != null && !directory.isBlank();
    }
}
