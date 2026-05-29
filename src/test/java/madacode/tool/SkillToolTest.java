package madacode.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import madacode.agent.AgentRunner;
import madacode.core.ConversationSession;
import madacode.core.ToolUseContext;
import madacode.provider.Model;
import madacode.provider.Provider;
import madacode.provider.ProviderRegistry;
import madacode.services.api.ApiClient;
import madacode.services.api.MadaApiClient;
import madacode.skill.BundledSkillLoader;
import madacode.skill.SkillRegistry;
import madacode.skill.SkillStateStore;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;

class SkillToolTest {

    private SkillTool tool;
    private ToolUseContext context;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BashTool());
        registry.register(new FileReadTool());
        registry.register(new FileWriteTool());
        registry.register(new FileEditTool());
        registry.register(new GlobTool());
        registry.register(new GrepTool());

        ProviderRegistry providerRegistry = ProviderRegistry.singleProvider(
                new Provider("anthropic", "sk-test",
                        java.net.URI.create("https://api.anthropic.com"),
                        "claude-opus-4-7",
                        List.of(new Model("claude-opus-4-7", 200_000))));
        ApiClient apiClient = new MadaApiClient(providerRegistry);
        AgentRunner runner = new AgentRunner(registry, apiClient, madacode.permission.PermissionGate.permissive());

        SkillStateStore store = new SkillStateStore(
                Path.of(System.getProperty("user.home"), ".mada/skills.json"));
        SkillRegistry skillReg = new SkillRegistry(store, new BundledSkillLoader());
        skillReg.reload();

        tool = new SkillTool(skillReg, runner);
        ConversationSession session = new ConversationSession();
        context = new ToolUseContext(
                Path.of(System.getProperty("user.dir")), session);
        mapper = new ObjectMapper();
    }

    @Test
    void skillToolHasCorrectName() {
        assertEquals("skill", tool.name());
    }

    @Test
    void skillToolIsReadOnly() {
        assertTrue(tool.isReadOnly());
    }

    @Test
    void skillToolHasSchemaWithSkillAndTask() throws Exception {
        var schema = tool.inputSchema(mapper);
        assertTrue(schema.has("properties"));
        var props = schema.get("properties");
        assertTrue(props.has("skill"));
        assertTrue(props.has("task"));
    }

    @Test
    void executeRequiresSkill() {
        var result = tool.execute(new SkillTool.Input("", ""), context);
        assertFalse(result.success());
        assertTrue(result.output().contains("required"));
    }

    @Test
    void executeUnknownSkill() {
        var result = tool.execute(
                new SkillTool.Input("nonexistent-skill", ""), context);
        assertFalse(result.success());
    }

    @Test
    void inlineSkillReturnsPrompt() {
        // code-review is bundled as inline — should return the prompt body
        var result = tool.execute(
                new SkillTool.Input("code-review", "review README"), context);
        assertTrue(result.success());
        assertTrue(result.output().contains("Code Review"));
        assertTrue(result.output().contains("review README"));
    }

    @Test
    void inlineSkillWithoutTask() {
        var result = tool.execute(
                new SkillTool.Input("code-review", ""), context);
        assertTrue(result.success());
        assertTrue(result.output().contains("Code Review"));
    }

    @Test
    void descriptionMentionsAvailableSkills() {
        String desc = tool.description();
        assertTrue(desc.contains("code-review"));
        assertTrue(desc.contains("simplify"));
    }

    @Test
    void simplifySkillReturnsBody() {
        var result = tool.execute(
                new SkillTool.Input("simplify", "clean up Main.java"), context);
        assertTrue(result.success());
        assertTrue(result.output().contains("Simplify"));
        assertTrue(result.output().contains("clean up Main.java"));
    }
}
