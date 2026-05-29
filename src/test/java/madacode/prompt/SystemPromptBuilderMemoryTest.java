package madacode.prompt;

import madacode.core.ToolResult;
import madacode.core.ToolUseContext;
import madacode.memory.MadaMdLoader;
import madacode.memory.MemoryFile;
import madacode.memory.MemoryLoader;
import madacode.memory.MemoryStore;
import madacode.tool.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SystemPromptBuilderMemoryTest {

    @TempDir
    Path tempDir;

    @Test
    void injectsMemorySectionAfterToolListAndBeforeAgentContext() throws IOException {
        Path cwd = tempDir.resolve("project");
        Files.createDirectories(cwd);
        Files.writeString(cwd.resolve("MADA.md"), "Always prefer small commits.");
        MemoryStore store = new MemoryStore(tempDir.resolve("memory"));
        store.write(new MemoryFile("Commit Style", "desc", MemoryFile.MemoryType.PROJECT,
                "Use phase commits.", null), "commit_style.md");

        MemoryLoader loader = new MemoryLoader(new MadaMdLoader(), store, true);
        SystemPromptBuilder builder = new SystemPromptBuilder("agent-specific instructions", loader);

        String prompt = builder.build(List.of(new StubTool("Read"), new StubTool("Grep")), cwd);

        int tools = prompt.indexOf("Available tools: Read, Grep");
        int memory = prompt.indexOf("## Project & user context");
        int agent = prompt.indexOf("agent-specific instructions");
        assertTrue(tools >= 0);
        assertTrue(memory > tools);
        assertTrue(agent > memory);
        assertTrue(prompt.contains("<mada-md"));
        assertTrue(prompt.contains("Always prefer small commits."));
        assertTrue(prompt.contains("<memory-index>"));
        assertTrue(prompt.contains("[Commit Style]"));
    }

    @Test
    void doesNotInjectMemoryWithoutCwd() throws IOException {
        Path cwd = tempDir.resolve("project");
        Files.createDirectories(cwd);
        Files.writeString(cwd.resolve("MADA.md"), "project rules");
        MemoryLoader loader = new MemoryLoader(
                new MadaMdLoader(), new MemoryStore(tempDir.resolve("memory")), true);

        String prompt = new SystemPromptBuilder(loader).build(List.of(new StubTool("Read")));

        assertTrue(prompt.contains("Available tools: Read"));
        assertFalse(prompt.contains("Project & user context"));
        assertFalse(prompt.contains("project rules"));
    }

    private record StubTool(String name) implements Tool<ObjectNode> {
            @Override
            public Class<ObjectNode> inputType() { return ObjectNode.class; }

        @Override
        public String description() {
            return "stub";
        }

        @Override
        public boolean isReadOnly() {
            return true;
        }

        @Override
        public ObjectNode inputSchema(ObjectMapper mapper) {
            ObjectNode schema = mapper.createObjectNode();
            schema.put("type", "object");
            schema.set("properties", mapper.createObjectNode());
            return schema;
        }

        @Override
        public ToolResult execute(ObjectNode input, ToolUseContext context) {
            return new ToolResult(name(), true, "ok");
        }
    }
}
