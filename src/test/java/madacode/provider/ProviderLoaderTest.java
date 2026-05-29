package madacode.provider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void missingConfigCreatesNeutralTemplateForNonInteractiveStartup() throws Exception {
        Path file = tempDir.resolve("providers.json");
        ProviderLoader loader = new ProviderLoader(file);

        TemplateCreatedException error = assertThrows(
                TemplateCreatedException.class, loader::load);

        assertTrue(error.getMessage().contains(file.toString()));
        String template = Files.readString(file);
        assertTrue(template.contains("\"name\": \"your-provider\""));
        assertTrue(template.contains("\"authToken\": \"YOUR-AUTH-TOKEN\""));
        assertTrue(template.contains("\"defaultModel\": \"your-model\""));
        assertFalse(template.toLowerCase().contains("claude"));
        assertFalse(template.toLowerCase().contains("anthropic"));
    }

    @Test
    void saveWritesLoadableProviderConfig() {
        Path file = tempDir.resolve("providers.json");
        ProviderLoader loader = new ProviderLoader(file);
        Provider provider = new Provider(
                "primary",
                "sk-test",
                URI.create("https://example.com"),
                "model-main",
                List.of(
                        new Model("model-main", Model.DEFAULT_CONTEXT_WINDOW),
                        new Model("model-fast", 128_000)));

        loader.save(List.of(provider));

        List<Provider> loaded = loader.load();
        assertEquals(1, loaded.size());
        assertEquals("primary", loaded.getFirst().name());
        assertEquals("sk-test", loaded.getFirst().authToken());
        assertEquals(URI.create("https://example.com"), loaded.getFirst().baseUrl());
        assertEquals("model-main", loaded.getFirst().defaultModel());
        assertEquals(List.of("model-main", "model-fast"),
                loaded.getFirst().models().stream().map(Model::name).toList());
        assertEquals(128_000, loaded.getFirst().models().get(1).contextWindow());
    }
}
