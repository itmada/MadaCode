package madacode.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MemoryStoreTest {

    @TempDir
    Path tempDir;

    private MemoryStore store;

    @BeforeEach
    void setUp() {
        store = new MemoryStore(tempDir);
    }

    @Test
    void writeAndReadRoundTrips() {
        MemoryFile mf = new MemoryFile("Test", "Desc", MemoryFile.MemoryType.USER, "body", null);
        store.write(mf, "user_test.md");

        var restored = store.read("user_test.md");
        assertTrue(restored.isPresent());
        assertEquals("Test", restored.get().name());
        assertEquals("body", restored.get().body());
    }

    @Test
    void writeUpdatesIndex() {
        MemoryFile mf = new MemoryFile("Indexed", "A test entry", MemoryFile.MemoryType.PROJECT, "b", null);
        store.write(mf, "project_indexed.md");

        String index = store.readIndex().orElseThrow();
        assertTrue(index.contains("[Indexed](project_indexed.md)"));
        assertTrue(index.contains("A test entry"));
    }

    @Test
    void repeatWriteReplacesIndexLine() {
        MemoryFile mf1 = new MemoryFile("First", "desc1", MemoryFile.MemoryType.USER, "a", null);
        MemoryFile mf2 = new MemoryFile("Second", "desc2", MemoryFile.MemoryType.USER, "b", null);
        store.write(mf1, "user_test.md");
        store.write(mf2, "user_test.md");

        String index = store.readIndex().orElseThrow();
        assertFalse(index.contains("[First]"));
        assertTrue(index.contains("[Second]"));
    }

    @Test
    void listAllReturnsWrittenFiles() {
        store.write(new MemoryFile("A", "d", MemoryFile.MemoryType.USER, "b", null), "user_a.md");
        store.write(new MemoryFile("B", "d", MemoryFile.MemoryType.FEEDBACK, "b", null), "feedback_b.md");

        List<MemoryFile> files = store.listAll();
        assertEquals(2, files.size());
        assertTrue(files.stream().anyMatch(f -> f.name().equals("A")));
        assertTrue(files.stream().anyMatch(f -> f.name().equals("B")));
    }

    @Test
    void readNonExistentReturnsEmpty() {
        assertTrue(store.read("nonexistent.md").isEmpty());
    }

    @Test
    void listAllSkipsIndexFile() {
        store.writeIndex("index content");
        List<MemoryFile> files = store.listAll();
        assertTrue(files.stream().noneMatch(f ->
                f.path() != null && f.path().getFileName().toString().equals(MemoryStore.INDEX_FILENAME)));
    }
}
