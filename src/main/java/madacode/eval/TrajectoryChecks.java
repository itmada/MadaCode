package madacode.eval;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Declarative assertions over the tools and files touched during an attempt. */
public record TrajectoryChecks(
        List<String> allowedTools,
        List<String> forbiddenTools,
        List<String> fileWhitelist,
        boolean requireReadBeforeEdit,
        Boolean gating) {

    public TrajectoryChecks {
        allowedTools = normalized(allowedTools);
        forbiddenTools = normalized(forbiddenTools);
        fileWhitelist = normalized(fileWhitelist);
        Set<String> overlap = new HashSet<>(allowedTools);
        overlap.retainAll(forbiddenTools);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(
                    "checks.trajectory tools cannot be both allowed and forbidden: " + overlap);
        }
        for (String path : fileWhitelist) {
            Path normalized = Path.of(path).normalize();
            if (normalized.isAbsolute()
                    || normalized.startsWith("..")
                    || path.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        "checks.trajectory.fileWhitelist must contain safe relative paths: " + path);
            }
        }
    }

    public boolean gatingOrDefault() {
        return gating == null || gating;
    }

    private static List<String> normalized(List<String> values) {
        return values == null
                ? List.of()
                : values.stream()
                        .map(value -> value == null ? "" : value.strip())
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .toList();
    }
}
