package madacode.tool;

import madacode.util.ToolNameNormalizer;

import java.util.Map;

/**
 * Canonicalizes tool names that appear in user-authored configuration.
 *
 * <p>{@link ToolNameNormalizer} handles spelling variants such as case, hyphens,
 * and camelCase. This class adds only role-level aliases whose normalized names
 * do not match MadaCode's registered tool names.
 */
public final class ToolNameCanonicalizer {

    private static final Map<String, String> STANDARD_ALIASES = Map.of(
            "task", ToolNames.AGENT);

    private ToolNameCanonicalizer() {}

    public static String canonicalize(String name) {
        String normalized = ToolNameNormalizer.normalize(name);
        if (normalized == null || normalized.isBlank()) {
            return null;
        }
        return STANDARD_ALIASES.getOrDefault(normalized, normalized);
    }
}
