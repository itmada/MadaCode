package madacode.memory;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Parses and serializes the minimal frontmatter in memory files.
 * Only handles {@code name}, {@code description}, {@code type} fields.
 * Deliberately avoids a full YAML parser dependency.
 */
final class MemoryFrontmatter {

    private static final Pattern KEY_VALUE = Pattern.compile("^([a-z]+):\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    private MemoryFrontmatter() {
    }

    static MemoryFile parse(String raw, Path source) {
        if (raw == null || !raw.startsWith("---\n")) {
            return null;
        }

        int end = raw.indexOf("\n---\n", 4);
        if (end < 0) {
            return null;
        }

        String name = null;
        String description = null;
        MemoryFile.MemoryType type = null;

        String[] headerLines = raw.substring(4, end).split("\n");
        for (String line : headerLines) {
            var m = KEY_VALUE.matcher(line.trim());
            if (!m.matches()) continue;
            String key = m.group(1).toLowerCase();
            String value = m.group(2).trim();
            switch (key) {
                case "name" -> name = value;
                case "description" -> description = value;
                case "type" -> type = MemoryFile.MemoryType.parse(value);
            }
        }

        if (name == null || description == null || type == null) {
            return null;
        }

        String body = raw.substring(end + 5).strip();
        return new MemoryFile(name, description, type, body, source);
    }

    static String serialize(MemoryFile file) {
        return "---\n"
                + "name: " + file.name() + "\n"
                + "description: " + file.description() + "\n"
                + "type: " + file.type().slug() + "\n"
                + "---\n\n"
                + file.body() + "\n";
    }
}
