package madacode.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MadaMdLoaderTest {

    @Test
    void loadsCwdFile(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("MADA.md"), "project rules");

        List<MadaMdLoader.LoadedFile> files = new MadaMdLoader().load(tempDir);

        assertEquals(1, files.size());
        // no parent MADA.md exists, so the cwd file is labelled "cwd"
        assertEquals("cwd", files.getFirst().source());
        assertTrue(files.getFirst().content().contains("project rules"));
    }

    @Test
    void loadsProjectRootFromSubdirectory(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("MADA.md"), "root");
        Path sub = tempDir.resolve("sub/deep");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("MADA.md"), "sub-level");

        List<MadaMdLoader.LoadedFile> files = new MadaMdLoader().load(sub);

        // project-root found at tempDir/MADA.md, cwd has its own
        assertEquals(2, files.size());
        assertEquals("project-root", files.get(0).source());
        assertTrue(files.get(0).content().contains("root"));
        assertEquals("cwd", files.get(1).source());
        assertTrue(files.get(1).content().contains("sub-level"));
    }

    @Test
    void cwdLevelOnlyNotDuplicated(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("MADA.md"), "only");

        List<MadaMdLoader.LoadedFile> files = new MadaMdLoader().load(tempDir);

        assertEquals(1, files.size());
        assertEquals("cwd", files.getFirst().source());
    }

    @Test
    void noMadaMdReturnsEmpty(@TempDir Path tempDir) {
        List<MadaMdLoader.LoadedFile> files = new MadaMdLoader().load(tempDir);
        assertTrue(files.isEmpty());
    }
}
