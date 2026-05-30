package madacode.memory;

import madacode.tool.ToolTestSupport;
import madacode.core.session.ConversationSession;
import madacode.core.engine.ToolUseContext;
import madacode.tool.MemorySaveTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MemorySaveToolTest {

    @TempDir
    Path tempDir;

    private MemoryStore store;
    private MemorySaveTool tool;
    private ToolUseContext context;

    @BeforeEach
    void setUp() {
        store = new MemoryStore(tempDir);
        tool = new MemorySaveTool(store);
        context = new ToolUseContext(tempDir, new ConversationSession(tempDir));
    }

    @Test
    void savesValidEntry() {
        ObjectNode input = buildInput("My Note", "A test entry", "user", "Body content.", "");

        var result = ToolTestSupport.invoke(tool, input, context);

        assertTrue(result.success());
        assertTrue(result.output().contains("Saved memory"));
        assertTrue(result.output().contains("My Note"));
        assertTrue(store.read("user_my_note.md").isPresent());
        assertTrue(store.readIndex().orElse("").contains("[My Note]"));
    }

    @Test
    void savesWithCustomFilename() {
        ObjectNode input = buildInput("Custom", "desc", "feedback", "body", "custom_name.md");

        var result = ToolTestSupport.invoke(tool, input, context);

        assertTrue(result.success());
        assertTrue(store.read("custom_name.md").isPresent());
    }

    @Test
    void rejectsInvalidType() {
        ObjectNode input = buildInput("Bad", "desc", "invalid_type", "body", "");

        var result = ToolTestSupport.invoke(tool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("Invalid type"));
    }

    @Test
    void autoGeneratesSlugFilename() {
        ObjectNode input = buildInput("My Great Memory!", "desc", "project", "body", "");

        var result = ToolTestSupport.invoke(tool, input, context);

        assertTrue(result.success());
        assertTrue(store.read("project_my_great_memory.md").isPresent());
    }

    private ObjectNode buildInput(String name, String desc, String type, String body, String file) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode input = mapper.createObjectNode();
        input.put("name", name);
        input.put("description", desc);
        input.put("type", type);
        input.put("body", body);
        if (!file.isEmpty()) input.put("file", file);
        return input;
    }
}
