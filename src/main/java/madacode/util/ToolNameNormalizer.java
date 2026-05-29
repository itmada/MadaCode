package madacode.util;

import java.util.List;
import java.util.Locale;

/**
 * Normalizes tool names so authors of skill/agent markdown files can write
 * {@code FileRead}, {@code file-read}, or {@code file_read} and all match
 * the registered tool name.
 *
 * <p>Rules:
 * <ul>
 *   <li>lowercase via {@link Locale#ROOT} (locale-independent)</li>
 *   <li>hyphens replaced with underscores</li>
 * </ul>
 */
public final class ToolNameNormalizer {

    private ToolNameNormalizer() {}

    public static String normalize(String name) {
        if (name == null) return null;
        String withUnderscores = name
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replace('-', '_');
        return withUnderscores.toLowerCase(Locale.ROOT);
    }

    public static List<String> normalize(List<String> names) {
        if (names == null) return List.of();
        return names.stream()
                .map(ToolNameNormalizer::normalize)
                .toList();
    }
}
