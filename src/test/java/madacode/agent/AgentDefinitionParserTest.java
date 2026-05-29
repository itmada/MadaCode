package madacode.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDefinitionParserTest {

    private static final Path FAKE_PATH = Path.of("/fake/agent.md");

    @Test
    void parsesFullFrontmatter() {
        String content = """
                ---
                name: explorer
                description: Explores files.
                when_to_use: When you need to find code.
                allowed_tools: [file_read, glob, grep]
                disallowed_tools: [agent, bash]
                max_iterations: 6
                max_tool_calls: 20
                ---
                You are a code exploration agent.
                """;

        Optional<AgentDefinition> result = AgentDefinitionParser.parse(content, "fallback", FAKE_PATH);

        assertTrue(result.isPresent());
        AgentDefinition def = result.get();
        assertEquals("explorer", def.agentType());
        assertEquals("Explores files.", def.description());
        assertEquals("When you need to find code.", def.whenToUse());
        assertTrue(def.systemPrompt().contains("code exploration agent"));
        assertTrue(def.allowedTools().contains("file_read"));
        assertTrue(def.disallowedTools().contains("agent"));
        assertEquals(6, def.maxIterations());
        assertEquals(20, def.maxToolCalls());
    }

    @Test
    void usesFallbackNameWhenMissing() {
        String content = """
                ---
                description: An agent.
                ---
                body text
                """;
        Optional<AgentDefinition> result = AgentDefinitionParser.parse(content, "myagent", FAKE_PATH);

        assertTrue(result.isPresent());
        assertEquals("myagent", result.get().agentType());
    }

    @Test
    void emptyBodyReturnsEmpty() {
        String content = """
                ---
                name: x
                description: d
                ---
                """;
        assertTrue(AgentDefinitionParser.parse(content, "x", FAKE_PATH).isEmpty());
    }

    @Test
    void zeroMaxIterationsSanitizedToDefault() {
        String content = """
                ---
                name: x
                max_iterations: 0
                ---
                body
                """;
        Optional<AgentDefinition> result = AgentDefinitionParser.parse(content, "x", FAKE_PATH);
        assertTrue(result.isPresent());
        assertEquals(15, result.get().maxIterations());
    }

    @Test
    void negativeMaxToolCallsSanitizedToDefault() {
        String content = """
                ---
                name: x
                max_tool_calls: -5
                ---
                body
                """;
        Optional<AgentDefinition> result = AgentDefinitionParser.parse(content, "x", FAKE_PATH);
        assertTrue(result.isPresent());
        assertEquals(50, result.get().maxToolCalls());
    }

    @Test
    void toolNamesAreNormalized() {
        String content = """
                ---
                name: x
                allowed_tools: [FileRead, GLOB, web-fetch]
                ---
                body
                """;
        Optional<AgentDefinition> result = AgentDefinitionParser.parse(content, "x", FAKE_PATH);
        assertTrue(result.isPresent());
        assertTrue(result.get().allowedTools().contains("file_read"));
        assertTrue(result.get().allowedTools().contains("glob"));
        assertTrue(result.get().allowedTools().contains("web_fetch"));
    }

    @Test
    void missingOptionalFieldsUseDefaults() {
        String content = """
                ---
                name: minimal
                ---
                just a body
                """;
        Optional<AgentDefinition> result = AgentDefinitionParser.parse(content, "minimal", FAKE_PATH);

        assertTrue(result.isPresent());
        AgentDefinition def = result.get();
        assertEquals("", def.description());
        assertEquals("", def.whenToUse());
        assertTrue(def.allowedTools().isEmpty());
        assertTrue(def.disallowedTools().isEmpty());
        assertEquals(15, def.maxIterations());
        assertEquals(50, def.maxToolCalls());
    }
}
