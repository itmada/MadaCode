package madacode.bootstrap;

import madacode.provider.Model;
import madacode.provider.Provider;
import madacode.provider.ProviderLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSetupWizardTest {

    @TempDir
    Path tempDir;

    @Test
    void cardRendersFieldsInRequestedOrderAndMasksToken() {
        ProviderSetupWizard.Draft draft = new ProviderSetupWizard.Draft();
        draft.set(ProviderSetupWizard.Field.PROVIDER_NAME, "primary");
        draft.set(ProviderSetupWizard.Field.BASE_URL, "https://example.com");
        draft.set(ProviderSetupWizard.Field.AUTH_TOKEN, "sk-test");
        draft.set(ProviderSetupWizard.Field.DEFAULT_MODEL, "model-main");
        draft.set(ProviderSetupWizard.Field.OTHER_MODELS, "model-fast");
        var lines = ProviderSetupWizard.card(
                draft,
                "",
                Path.of("/tmp/providers.json"),
                80);

        String text = stripAnsi(String.join("\n", lines));
        assertTrue(text.contains("Provider name"));
        assertTrue(text.contains("Base URL"));
        assertTrue(text.contains("Auth token"));
        assertTrue(text.contains("Default model"));
        assertTrue(text.contains("Other models"));
        assertTrue(text.contains("MadaCode needs a model provider before it can run."));
        assertTrue(text.contains("Create /tmp/providers.json"));
        assertTrue(text.indexOf("Provider name") < text.indexOf("Base URL"));
        assertTrue(text.indexOf("Base URL") < text.indexOf("Auth token"));
        assertTrue(text.indexOf("Auth token") < text.indexOf("Default model"));
        assertTrue(text.indexOf("Default model") < text.indexOf("Other models"));
        assertFalse(text.contains("sk-test"));
        assertTrue(text.contains("********"));
        assertFalse(text.toLowerCase().contains("claude"));
        assertFalse(text.toLowerCase().contains("anthropic"));
    }

    @Test
    void blankFieldsRenderAsEmptyStateWithoutPretendingTokenIsFilled() {
        ProviderSetupWizard.Draft draft = new ProviderSetupWizard.Draft();
        var lines = ProviderSetupWizard.card(
                draft,
                "",
                Path.of("/tmp/providers.json"),
                80);

        String text = stripAnsi(String.join("\n", lines));
        // Check that each field label appears and empty state "-" is rendered
        assertTrue(text.contains("Provider name"), "should contain Provider name label");
        assertTrue(text.contains(" -"), "should contain empty state marker");
        assertFalse(text.contains("********"), "should not have token mask when blank");
    }

    @Test
    void activeTokenUsesRealLengthMaskForCursorPosition() {
        ProviderSetupWizard.Draft draft = new ProviderSetupWizard.Draft();
        draft.set(ProviderSetupWizard.Field.AUTH_TOKEN, "abcdefghijkl", 10);
        var lines = ProviderSetupWizard.card(
                draft,
                ProviderSetupWizard.Field.AUTH_TOKEN,
                Path.of("/tmp/providers.json"),
                80);

        String text = stripAnsi(String.join("\n", lines));
        assertFalse(text.contains("abcdefghijkl"), "should not expose actual token");
        assertTrue(text.contains("**********█**"), "should show masked cursor position");
    }

    @Test
    void activeFieldShowsCursorMarker() {
        ProviderSetupWizard.Draft draft = new ProviderSetupWizard.Draft();
        draft.set(ProviderSetupWizard.Field.PROVIDER_NAME, "primary", 3);
        var lines = ProviderSetupWizard.card(
                draft,
                ProviderSetupWizard.Field.PROVIDER_NAME,
                Path.of("/tmp/providers.json"),
                80);

        String text = stripAnsi(String.join("\n", lines));
        assertTrue(text.contains("pri█mary"), "should show cursor marker in active field");
    }

    @Test
    void draftToProviderMergesDefaultAndOtherModelsAndDeduplicates() {
        ProviderSetupWizard.Draft draft = new ProviderSetupWizard.Draft();
        draft.set(ProviderSetupWizard.Field.PROVIDER_NAME, "primary");
        draft.set(ProviderSetupWizard.Field.BASE_URL, "https://example.com");
        draft.set(ProviderSetupWizard.Field.AUTH_TOKEN, "sk-test");
        draft.set(ProviderSetupWizard.Field.DEFAULT_MODEL, "model-main");
        draft.set(ProviderSetupWizard.Field.OTHER_MODELS, " model-fast , model-main , model-fast , model-extra ");

        Provider provider = draft.toProvider();

        assertEquals("primary", provider.name());
        assertEquals("sk-test", provider.authToken());
        assertEquals(URI.create("https://example.com"), provider.baseUrl());
        assertEquals("model-main", provider.defaultModel());
        assertEquals(List.of("model-main", "model-fast", "model-extra"),
                provider.models().stream().map(Model::name).toList());
    }

    @Test
    void draftValidationRejectsInvalidUrl() {
        ProviderSetupWizard.Draft draft = new ProviderSetupWizard.Draft();
        draft.set(ProviderSetupWizard.Field.PROVIDER_NAME, "primary");
        draft.set(ProviderSetupWizard.Field.BASE_URL, "not-a-url");
        draft.set(ProviderSetupWizard.Field.AUTH_TOKEN, "sk-test");
        draft.set(ProviderSetupWizard.Field.DEFAULT_MODEL, "model-main");
        draft.set(ProviderSetupWizard.Field.OTHER_MODELS, "model-fast");

        assertThrows(IllegalArgumentException.class, draft::toProvider);
    }

    @Test
    void draftValidationRejectsBlankDefaultModel() {
        ProviderSetupWizard.Draft draft = new ProviderSetupWizard.Draft();
        draft.set(ProviderSetupWizard.Field.PROVIDER_NAME, "primary");
        draft.set(ProviderSetupWizard.Field.BASE_URL, "https://example.com");
        draft.set(ProviderSetupWizard.Field.AUTH_TOKEN, "sk-test");
        draft.set(ProviderSetupWizard.Field.DEFAULT_MODEL, "");
        draft.set(ProviderSetupWizard.Field.OTHER_MODELS, "model-fast");

        assertThrows(IllegalArgumentException.class, draft::toProvider);
    }

    @Test
    void cardDoesNotRenderProviderDefaultsForBlankDraft() {
        ProviderSetupWizard.Draft draft = new ProviderSetupWizard.Draft();
        var lines = ProviderSetupWizard.card(
                draft,
                "",
                Path.of("/tmp/providers.json"),
                80);

        String text = stripAnsi(String.join("\n", lines));
        assertFalse(text.toLowerCase().contains("claude"), "should not contain provider defaults");
        assertFalse(text.toLowerCase().contains("anthropic"), "should not contain provider defaults");
    }

    @Test
    void loaderSaveCanPersistWizardDraftOutput() {
        Path file = tempDir.resolve("providers.json");
        ProviderLoader loader = new ProviderLoader(file);
        Provider provider = new Provider(
                "primary",
                "sk-test",
                URI.create("https://example.com"),
                "model-main",
                List.of(
                        new Model("model-main", Model.DEFAULT_CONTEXT_WINDOW),
                        new Model("model-fast", Model.DEFAULT_CONTEXT_WINDOW)));

        loader.save(List.of(provider));

        assertTrue(file.toFile().isFile());
    }

    private static String stripAnsi(String text) {
        return text.replaceAll("\\x1b\\[[0-9;]*m", "");
    }
}
