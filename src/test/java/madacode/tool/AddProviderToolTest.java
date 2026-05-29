package madacode.tool;

import madacode.cli.FakeUserPromptChannel;
import madacode.core.ConversationSession;
import madacode.core.ToolResult;
import madacode.core.ToolUseContext;
import madacode.provider.Model;
import madacode.provider.Provider;
import madacode.provider.ProviderLoader;
import madacode.provider.ProviderRegistry;
import madacode.provider.ProviderStateStore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AddProviderToolTest {

    @TempDir
    Path tempDir;

    private AddProviderTool tool;
    private ProviderRegistry registry;
    private ProviderLoader loader;
    private ConversationSession session;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        registry = new ProviderRegistry(
                List.of(new Provider("existing", "token", URI.create("https://api.example.com"),
                        "m1", List.of(new Model("m1", Model.DEFAULT_CONTEXT_WINDOW)))),
                ProviderStateStore.inMemory());
        loader = new ProviderLoader(tempDir.resolve("providers.json"));
        tool = new AddProviderTool(registry, loader);
        session = new ConversationSession();
        mapper = new ObjectMapper();
    }

    private ToolUseContext ctx(madacode.cli.UserPromptChannel channel) {
        return new ToolUseContext(Path.of(System.getProperty("user.dir")), session,
                0, 1, madacode.core.CancellationToken.never(), channel);
    }

    private ObjectNode buildInput(String name, String baseUrl, String defaultModel, List<String> models) {
        ObjectNode input = mapper.createObjectNode();
        if (name != null) input.put("name", name);
        if (baseUrl != null) input.put("baseUrl", baseUrl);
        if (defaultModel != null) input.put("defaultModel", defaultModel);
        if (models != null) {
            ArrayNode modelsNode = mapper.createArrayNode();
            for (String m : models) {
                modelsNode.add(m);
            }
            input.set("models", modelsNode);
        }
        return input;
    }

    // ---- happy paths ------------------------------------------------------

    @Test
    void addsProviderSuccessfully() throws Exception {
        FakeUserPromptChannel channel = new FakeUserPromptChannel().queueAnswer("sk-xxx");
        ObjectNode input = buildInput("deepseek", "https://api.deepseek.com",
                "deepseek-chat", List.of("deepseek-chat", "deepseek-reasoner"));

        ToolResult result = ToolTestSupport.invoke(tool, input, ctx(channel));

        assertTrue(result.success(), "Should succeed. Got: " + result.output());
        assertTrue(registry.find("deepseek").isPresent(), "Provider should exist in registry");
        Provider p = registry.find("deepseek").get();
        assertEquals(2, p.models().size());
        assertEquals("deepseek-chat", p.defaultModel());
        assertFalse(result.output().contains("sk-xxx"), "Token must not appear in output");

        // Verify persistence
        List<Provider> loaded = loader.load();
        assertTrue(loaded.stream().anyMatch(x -> x.name().equals("deepseek")),
                "Provider should be persisted to file");
    }

    @Test
    void preservesPromptedTokenExactly() {
        FakeUserPromptChannel channel = new FakeUserPromptChannel().queueAnswer("  sk-with-spaces  ");
        ObjectNode input = buildInput("openrouter", "https://openrouter.ai/api",
                "model-main", List.of("model-main"));

        ToolResult result = ToolTestSupport.invoke(tool, input, ctx(channel));

        assertTrue(result.success(), "Should succeed. Got: " + result.output());
        assertEquals("  sk-with-spaces  ",
                registry.find("openrouter").orElseThrow().authToken());
    }

    @Test
    void defaultsModelsToSingletonList() {
        FakeUserPromptChannel channel = new FakeUserPromptChannel().queueAnswer("sk-xxx");
        ObjectNode input = buildInput("openai", "https://api.openai.com",
                "gpt-4o", null);

        ToolResult result = ToolTestSupport.invoke(tool, input, ctx(channel));

        assertTrue(result.success(), "Should succeed. Got: " + result.output());
        Provider p = registry.find("openai").orElseThrow();
        assertEquals(1, p.models().size());
        assertEquals("gpt-4o", p.models().getFirst().name());
    }

    @Test
    void splitsModelInputOnWhitespace() {
        FakeUserPromptChannel channel = new FakeUserPromptChannel().queueAnswer("sk-xxx");
        ObjectNode input = buildInput("openai", "https://api.openai.com",
                "gpt-4o", List.of("gpt-4o   gpt-4.1\n o3-mini"));

        ToolResult result = ToolTestSupport.invoke(tool, input, ctx(channel));

        assertTrue(result.success(), "Should succeed. Got: " + result.output());
        Provider p = registry.find("openai").orElseThrow();
        assertEquals(List.of("gpt-4o", "gpt-4.1", "o3-mini"),
                p.models().stream().map(Model::name).toList());
    }

    @Test
    void doesNotSplitModelInputOnComma() {
        FakeUserPromptChannel channel = new FakeUserPromptChannel().queueAnswer("sk-xxx");
        ObjectNode input = buildInput("openai", "https://api.openai.com",
                "gpt-4o", List.of("gpt-4o,gpt-4.1"));

        ToolResult result = ToolTestSupport.invoke(tool, input, ctx(channel));

        assertTrue(result.success(), "Should succeed. Got: " + result.output());
        Provider p = registry.find("openai").orElseThrow();
        assertEquals(List.of("gpt-4o", "gpt-4o,gpt-4.1"),
                p.models().stream().map(Model::name).toList());
    }

    // ---- failure cases ----------------------------------------------------

    @Test
    void cancelTokenReturnsFailure() {
        FakeUserPromptChannel channel = new FakeUserPromptChannel().queueCancel();
        ObjectNode input = buildInput("deepseek", "https://api.deepseek.com",
                "deepseek-chat", List.of("deepseek-chat"));

        ToolResult result = ToolTestSupport.invoke(tool, input, ctx(channel));

        assertFalse(result.success());
        assertFalse(registry.find("deepseek").isPresent(), "Provider should not be added");
        assertFalse(loader.exists(), "File should not be created");
    }

    @Test
    void headlessChannelReturnsFailure() {
        FakeUserPromptChannel unavailable = new FakeUserPromptChannel().setUnavailable();
        ObjectNode input = buildInput("deepseek", "https://api.deepseek.com",
                "deepseek-chat", List.of("deepseek-chat"));

        ToolResult result = ToolTestSupport.invoke(tool, input, ctx(unavailable));

        assertFalse(result.success());
        assertFalse(registry.find("deepseek").isPresent());
    }

    @Test
    void duplicateNameReturnsFailure() {
        FakeUserPromptChannel channel = new FakeUserPromptChannel().queueAnswer("sk-xxx");
        ObjectNode input = buildInput("existing", "https://api.example.com",
                "m1", List.of("m1"));

        ToolResult result = ToolTestSupport.invoke(tool, input, ctx(channel));

        assertFalse(result.success());
        assertTrue(result.output().toLowerCase().contains("duplicate"),
                "Should mention duplicate. Got: " + result.output());
    }

    @Test
    void invalidUrlReturnsFailureWithoutPrompting() {
        boolean[] prompted = {false};
        madacode.cli.UserPromptChannel channel = new madacode.cli.UserPromptChannel() {
            @Override public boolean isAvailable() { return true; }
            @Override public java.util.Optional<String> chooseOne(String title, java.util.List<ChannelOption> options) {
                return java.util.Optional.empty();
            }
            @Override public java.util.Optional<java.util.List<String>> chooseMany(String title, java.util.List<ChannelOption> options) {
                return java.util.Optional.empty();
            }
            @Override
            public java.util.Optional<String> freeText(String prompt) {
                prompted[0] = true;
                return java.util.Optional.empty();
            }
            @Override public boolean confirm(String prompt) { return false; }
        };

        // Test ftp://
        ObjectNode input1 = buildInput("test1", "ftp://example.com", "m1", List.of("m1"));
        ToolResult result1 = ToolTestSupport.invoke(tool, input1, ctx(channel));
        assertFalse(result1.success());

        // Test not a URL
        ObjectNode input2 = buildInput("test2", "notaurl", "m1", List.of("m1"));
        ToolResult result2 = ToolTestSupport.invoke(tool, input2, ctx(channel));
        assertFalse(result2.success());

        assertFalse(prompted[0], "Channel should not be prompted for invalid URLs");
    }

    @Test
    void blankNameReturnsFailure() {
        FakeUserPromptChannel channel = new FakeUserPromptChannel();
        ObjectNode input = buildInput("  ", "https://api.example.com", "m1", List.of("m1"));

        ToolResult result = ToolTestSupport.invoke(tool, input, ctx(channel));

        assertFalse(result.success());
        assertTrue(result.output().contains("name is required"));
    }

    @Test
    void blankDefaultModelReturnsFailure() {
        FakeUserPromptChannel channel = new FakeUserPromptChannel();
        ObjectNode input = buildInput("test", "https://api.example.com", "  ", List.of("m1"));

        ToolResult result = ToolTestSupport.invoke(tool, input, ctx(channel));

        assertFalse(result.success());
        assertTrue(result.output().contains("defaultModel is required"));
    }

    @Test
    void persistFailureRollsBackMemory() throws Exception {
        Path notDirectory = tempDir.resolve("not-a-directory");
        Files.writeString(notDirectory, "already a file");
        ProviderLoader badLoader = new ProviderLoader(notDirectory.resolve("providers.json"));
        AddProviderTool badTool = new AddProviderTool(registry, badLoader);

        FakeUserPromptChannel channel = new FakeUserPromptChannel().queueAnswer("sk-xxx");
        ObjectNode input = buildInput("newprovider", "https://api.example.com",
                "m1", List.of("m1"));

        ToolResult result = ToolTestSupport.invoke(badTool, input, ctx(channel));

        assertFalse(result.success());
        assertFalse(registry.find("newprovider").isPresent(),
                "Provider should be rolled back from memory");
    }

    @Test
    void reservedNameResetReturnsFailure() {
        FakeUserPromptChannel channel = new FakeUserPromptChannel().queueAnswer("sk-xxx");
        ObjectNode input = buildInput("reset", "https://api.example.com",
                "m1", List.of("m1"));

        ToolResult result = ToolTestSupport.invoke(tool, input, ctx(channel));

        assertFalse(result.success());
        assertTrue(result.output().toLowerCase().contains("reserved"),
                "Should mention reserved. Got: " + result.output());
    }
}
