package madacode.tool;

import madacode.util.ToolNameNormalizer;

import java.util.Map;

/**
 * Canonicalizes tool names that appear in user-authored configuration.
 *
 * <p>{@link ToolNameNormalizer} handles spelling variants such as case, hyphens,
 * and camelCase. This class adds semantic aliases whose normalized names do not
 * match MadaCode's registered tool names, e.g. Claude-style {@code Edit} maps to
 * {@code file_edit}.
 */
public final class ToolNameCanonicalizer {

    private static final Map<String, String> STANDARD_ALIASES = Map.of(
            "read", ToolNames.FILE_READ,
            "edit", ToolNames.FILE_EDIT,
            "write", ToolNames.FILE_WRITE,
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
