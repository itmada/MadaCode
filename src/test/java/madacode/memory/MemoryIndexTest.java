package madacode.memory;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MemoryIndexTest {

    @Test
    void underLimitReturnsUnchanged() {
        String content = "short content\n";
        assertEquals(content, MemoryIndex.truncate(content));
    }

    @Test
    void overLineLimitTruncates() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            sb.append("line ").append(i).append('\n');
        }
        String result = MemoryIndex.truncate(sb.toString());
        String[] lines = result.split("\n");
        assertTrue(lines.length < 250);
        assertTrue(result.contains("lines truncated"));
    }

    @Test
    void overByteLimitTruncatesAtLineBoundary() {
        // Use long lines (fewer than 200) to avoid triggering line-count truncation first
        StringBuilder sb = new StringBuilder();
        while (sb.toString().getBytes(StandardCharsets.UTF_8).length < MemoryIndex.MAX_BYTES + 2000) {
            sb.append("a".repeat(4000)).append('\n');
        }
        String result = MemoryIndex.truncate(sb.toString());
        assertTrue(result.length() < sb.length());
        assertTrue(result.contains("truncated at"));
    }

    @Test
    void emptyReturnsEmpty() {
        assertEquals("", MemoryIndex.truncate(""));
        assertEquals("", MemoryIndex.truncate(null));
    }
}
