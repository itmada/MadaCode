package madacode.memory;

import java.nio.charset.StandardCharsets;

final class MemoryIndex {

    static final int MAX_LINES = 200;
    static final int MAX_BYTES = 25 * 1024;

    private MemoryIndex() {
    }

    static String truncate(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }

        String[] lines = content.split("\n", -1);
        if (lines.length > MAX_LINES) {
            String[] kept = new String[101]; // 100 head + 1 placeholder
            System.arraycopy(lines, 0, kept, 0, 100);
            kept[100] = "[... " + (lines.length - 150) + " lines truncated ...]";
            String[] tail = new String[50];
            System.arraycopy(lines, lines.length - 50, tail, 0, 50);
            StringBuilder sb = new StringBuilder();
            for (String s : kept) sb.append(s).append('\n');
            for (String s : tail) sb.append(s).append('\n');
            return sb.toString().stripTrailing();
        }

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BYTES) {
            return content;
        }

        // Truncate to byte limit at a line boundary
        String withinBytes = new String(bytes, 0, MAX_BYTES, StandardCharsets.UTF_8);
        int lastNewline = withinBytes.lastIndexOf('\n');
        return lastNewline > 0
                ? withinBytes.substring(0, lastNewline)
                        + "\n[... truncated at " + MAX_BYTES / 1024 + "KB ...]"
                : withinBytes;
    }
}
