package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolActivitySummaryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void summarizesMajorToolShapes() {
        ObjectNode read = mapper.createObjectNode().put("path", "README.md");
        assertEquals("Reading README.md", ToolActivitySummary.summarize("file_read", read));

        ObjectNode bash = mapper.createObjectNode().put("command", "pwd");
        assertEquals("Running pwd", ToolActivitySummary.summarize("bash", bash));

        ObjectNode agent = mapper.createObjectNode()
                .put("subagent_type", "explorer")
                .put("description", "find files");
        assertEquals("Agent(explorer): find files", ToolActivitySummary.summarize("agent", agent));
    }

    @Test
    void projectionLinesAreSanitizedAndBounded() {
        String line = ToolActivitySummary.asProjectionLine("bash", mapper.createObjectNode().put("command",
                "echo one\necho two\t" + "x".repeat(200)));

        assertTrue(line.startsWith("▸ Running echo one echo two"), line);
        assertTrue(line.length() < 140, line);
        assertTrue(!line.contains("\n"), line);
        assertTrue(!line.contains("\t"), line);
    }
}
