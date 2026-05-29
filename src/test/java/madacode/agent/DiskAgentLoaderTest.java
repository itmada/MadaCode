package madacode.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskAgentLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void missingDirReturnsEmpty() {
        assertTrue(new DiskAgentLoader(tempDir.resolve("nope")).load().isEmpty());
    }

    @Test
    void nullPathReturnsEmpty() {
        assertTrue(new DiskAgentLoader(null).load().isEmpty());
    }

    @Test
    void loadsMultipleMdFiles() throws IOException {
        write("alpha.md", "---\nname: alpha\n---\nbody alpha");
        write("beta.md", "---\nname: beta\n---\nbody beta");

        List<AgentDefinition> defs = new DiskAgentLoader(tempDir).load();

        assertEquals(2, defs.size());
        assertEquals("alpha", defs.get(0).agentType());
        assertEquals("beta", defs.get(1).agentType());
    }

    @Test
    void skipsNonMdFiles() throws IOException {
        write("good.md", "---\nname: good\n---\nbody");
        write("readme.txt", "ignore me");

        List<AgentDefinition> defs = new DiskAgentLoader(tempDir).load();

        assertEquals(1, defs.size());
        assertEquals("good", defs.get(0).agentType());
    }

    @Test
    void fallbackNameStripsMdSuffix() throws IOException {
        write("anonymous.md", "---\ndescription: no name field\n---\nbody");

        List<AgentDefinition> defs = new DiskAgentLoader(tempDir).load();

        assertEquals(1, defs.size());
        assertEquals("anonymous", defs.get(0).agentType());
    }

    @Test
    void invalidFileSkippedOthersLoad() throws IOException {
        write("good.md", "---\nname: good\n---\nbody");
        write("bad.md", "---\nname: bad\n---\n");

        List<AgentDefinition> defs = new DiskAgentLoader(tempDir).load();

        assertEquals(1, defs.size());
        assertEquals("good", defs.get(0).agentType());
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(tempDir.resolve(name), content);
    }
}
