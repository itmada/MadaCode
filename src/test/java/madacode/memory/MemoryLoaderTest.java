package madacode.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MemoryLoaderTest {

    @TempDir
    Path tempDir;

    private MadaMdLoader madaMdLoader;
    private MemoryStore store;
    private Path cwd;

    @BeforeEach
    void setUp() throws IOException {
        cwd = tempDir.resolve("project");
        Files.createDirectories(cwd);
        madaMdLoader = new MadaMdLoader();
        store = new MemoryStore(tempDir.resolve("memory"));
    }

    @Test
    void disabledReturnsEmpty() {
        MemoryLoader loader = new MemoryLoader(madaMdLoader, store, false);
        assertTrue(loader.renderForSystemPrompt(cwd).isEmpty());
    }

    @Test
    void madaMdOnly() throws IOException {
        Files.writeString(cwd.resolve("MADA.md"), "project rules");
        MemoryLoader loader = new MemoryLoader(madaMdLoader, store, true);

        Optional<String> result = loader.renderForSystemPrompt(cwd);
        assertTrue(result.isPresent());
        String rendered = result.get();
        assertTrue(rendered.contains("mada-md"));
        assertTrue(rendered.contains("project rules"));
    }

    @Test
    void memoryIndexOnly() {
        store.write(new MemoryFile("X", "desc", MemoryFile.MemoryType.PROJECT, "b", null), "project_x.md");
        MemoryLoader loader = new MemoryLoader(madaMdLoader, store, true);

        Optional<String> result = loader.renderForSystemPrompt(cwd);
        assertTrue(result.isPresent());
        String rendered = result.get();
        assertTrue(rendered.contains("memory-index"));
        assertTrue(rendered.contains("[X]"));
    }

    @Test
    void bothPresentMadaMdFirst() throws IOException {
        Files.writeString(cwd.resolve("MADA.md"), "rules");
        store.write(new MemoryFile("Note", "desc", MemoryFile.MemoryType.USER, "body", null), "user_note.md");
        MemoryLoader loader = new MemoryLoader(madaMdLoader, store, true);

        Optional<String> result = loader.renderForSystemPrompt(cwd);
        assertTrue(result.isPresent());
        String rendered = result.get();
        int madaPos = rendered.indexOf("<mada-md");
        int memPos = rendered.indexOf("<memory-index");
        assertTrue(madaPos >= 0);
        assertTrue(memPos > madaPos, "MADA.md should come before MEMORY.md");
    }

    @Test
    void neitherPresentReturnsEmpty() {
        MemoryLoader loader = new MemoryLoader(madaMdLoader, store, true);
        assertTrue(loader.renderForSystemPrompt(cwd).isEmpty());
    }
}
