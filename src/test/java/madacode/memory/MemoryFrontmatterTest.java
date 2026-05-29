package madacode.memory;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MemoryFrontmatterTest {

    @Test
    void parseValid() {
        String raw = "---\nname: Test entry\ndescription: A test memory\ntype: user\n---\n\nBody text.";
        var mf = MemoryFrontmatter.parse(raw, Path.of("test.md"));
        assertNotNull(mf);
        assertEquals("Test entry", mf.name());
        assertEquals("A test memory", mf.description());
        assertEquals(MemoryFile.MemoryType.USER, mf.type());
        assertEquals("Body text.", mf.body());
    }

    @Test
    void parseMissingNameReturnsNull() {
        String raw = "---\ndescription: desc\ntype: project\n---\n\nbody";
        assertNull(MemoryFrontmatter.parse(raw, Path.of("test.md")));
    }

    @Test
    void parseInvalidTypeReturnsNull() {
        String raw = "---\nname: X\ndescription: Y\ntype: invalid\n---\n\nbody";
        assertNull(MemoryFrontmatter.parse(raw, Path.of("test.md")));
    }

    @Test
    void parseNoFrontmatterReturnsNull() {
        String raw = "Just a plain body, no frontmatter.";
        assertNull(MemoryFrontmatter.parse(raw, Path.of("test.md")));
    }

    @Test
    void parseNullReturnsNull() {
        assertNull(MemoryFrontmatter.parse(null, Path.of("test.md")));
    }

    @Test
    void serializeRoundTrip() {
        MemoryFile mf = new MemoryFile("Test", "Desc", MemoryFile.MemoryType.FEEDBACK, "Body.", null);
        String serialized = MemoryFrontmatter.serialize(mf);
        MemoryFile parsed = MemoryFrontmatter.parse(serialized, Path.of("test.md"));
        assertNotNull(parsed);
        assertEquals("Test", parsed.name());
        assertEquals("Desc", parsed.description());
        assertEquals(MemoryFile.MemoryType.FEEDBACK, parsed.type());
        assertEquals("Body.", parsed.body());
    }

    @Test
    void serializeContainsFrontmatterFields() {
        MemoryFile mf = new MemoryFile("X", "D", MemoryFile.MemoryType.REFERENCE, "B", null);
        String s = MemoryFrontmatter.serialize(mf);
        assertTrue(s.startsWith("---\n"));
        assertTrue(s.contains("\n---\n"));
        assertTrue(s.contains("name: X\n"));
        assertTrue(s.contains("type: reference\n"));
    }
}
