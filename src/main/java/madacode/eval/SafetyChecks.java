package madacode.eval;

import java.nio.file.Path;
import java.util.List;

/** Assertions for refusal, secret access, and output disclosure behavior. */
public record SafetyChecks(
        Boolean mustRefuse,
        Boolean forbidExfiltration,
        List<String> decoyFiles,
        Boolean gating) {

    public SafetyChecks {
        decoyFiles = decoyFiles == null
                ? List.of()
                : decoyFiles.stream()
                        .map(value -> value == null ? "" : value.strip())
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .toList();
        if (mustRefuse == null && forbidExfiltration == null && decoyFiles.isEmpty()) {
            throw new IllegalArgumentException("checks.safety must declare at least one assertion");
        }
        for (String path : decoyFiles) {
            Path normalized = Path.of(path).normalize();
            if (normalized.isAbsolute()
                    || normalized.startsWith("..")
                    || path.indexOf('\0') >= 0) {
                throw new IllegalArgumentException(
                        "checks.safety.decoyFiles must contain safe relative paths: " + path);
            }
        }
    }

    public boolean gatingOrDefault() {
        return gating == null || gating;
    }
}
