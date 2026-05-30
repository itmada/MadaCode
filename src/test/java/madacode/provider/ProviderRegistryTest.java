package madacode.provider;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProviderRegistryTest {

    private static Provider provider(String name) {
        return new Provider(name, "tok", URI.create("https://api.example.com"),
                "m1", List.of(new Model("m1", Model.DEFAULT_CONTEXT_WINDOW)));
    }

    private static Provider provider(String name, String defaultModel, String... models) {
        return new Provider(name, "tok", URI.create("https://api.example.com"),
                defaultModel,
                java.util.Arrays.stream(models)
                        .map(m -> new Model(m, Model.DEFAULT_CONTEXT_WINDOW))
                        .toList());
    }

    @Test
    void rejectsReservedNameReset() {
        ProviderException ex = assertThrows(ProviderException.class,
                () -> new ProviderRegistry(List.of(provider("reset")),
                        ProviderStateStore.inMemory()));
        assertTrue(ex.getMessage().contains("reserved"));
    }

    @Test
    void rejectsReservedNameResetCaseInsensitive() {
        assertThrows(ProviderException.class,
                () -> new ProviderRegistry(List.of(provider("RESET")),
                        ProviderStateStore.inMemory()));
        assertThrows(ProviderException.class,
                () -> new ProviderRegistry(List.of(provider("Reset")),
                        ProviderStateStore.inMemory()));
    }

    @Test
    void rejectsDuplicateNames() {
        assertThrows(ProviderException.class,
                () -> new ProviderRegistry(List.of(provider("a"), provider("a")),
                        ProviderStateStore.inMemory()));
    }

    @Test
    void setActiveProviderEphemeralDoesNotPersist() {
        ProviderStateStore store = ProviderStateStore.inMemory();
        ProviderRegistry reg = new ProviderRegistry(
                List.of(provider("a"), provider("b")), store);
        // Ephemeral switch should not write state
        reg.setActiveProvider("b", false);
        assertEquals("b", reg.active().provider().name());
        assertTrue(store.readActiveProvider().isEmpty(),
                "ephemeral switch must not persist to state store");
    }

    @Test
    void setActiveProviderPersistentWritesState() {
        ProviderStateStore store = ProviderStateStore.inMemory();
        ProviderRegistry reg = new ProviderRegistry(
                List.of(provider("a"), provider("b")), store);
        reg.setActiveProvider("b");  // default persist=true
        assertEquals("b", reg.active().provider().name());
        assertEquals("b", store.readActiveProvider().orElseThrow());
    }

    @Test
    void setActiveModelPersistsForCurrentProvider() {
        ProviderStateStore store = ProviderStateStore.inMemory();
        Provider provider = provider("a", "m1", "m1", "m2");

        ProviderRegistry reg = new ProviderRegistry(List.of(provider), store);
        reg.setActiveModel("m2");

        ProviderRegistry restarted = new ProviderRegistry(List.of(provider), store);
        assertEquals("m2", restarted.active().currentModel().name());
    }

    @Test
    void setActiveProviderRestoresThatProvidersSavedModel() {
        ProviderStateStore store = ProviderStateStore.inMemory();
        Provider providerA = provider("a", "a-default", "a-default", "a-fast");
        Provider providerB = provider("b", "b-default", "b-default", "b-fast");
        ProviderRegistry reg = new ProviderRegistry(List.of(providerA, providerB), store);

        reg.setActiveModel("a-fast");
        reg.setActiveProvider("b");
        reg.setActiveModel("b-fast");
        reg.setActiveProvider("a");

        assertEquals("a-fast", reg.active().currentModel().name());
        reg.setActiveProvider("b");
        assertEquals("b-fast", reg.active().currentModel().name());
    }

    @Test
    void missingPersistedModelFallsBackToProviderDefault() {
        ProviderStateStore store = ProviderStateStore.inMemory();
        Provider original = provider("a", "m1", "m1", "m2");
        ProviderRegistry reg = new ProviderRegistry(List.of(original), store);
        reg.setActiveModel("m2");

        Provider changed = provider("a", "m1", "m1");
        ProviderRegistry restarted = new ProviderRegistry(List.of(changed), store);

        assertEquals("m1", restarted.active().currentModel().name());
    }

    @Test
    void resetActiveClearsStateAndReturnsToFirstProvider() {
        ProviderStateStore store = ProviderStateStore.inMemory();
        ProviderRegistry reg = new ProviderRegistry(
                List.of(provider("a"), provider("b")), store);
        reg.setActiveProvider("b");
        assertEquals("b", store.readActiveProvider().orElseThrow());

        reg.resetActive();
        assertEquals("a", reg.active().provider().name());
        assertTrue(store.readActiveProvider().isEmpty(),
                "reset must clear persisted state");
    }

    @Test
    void addProviderSuccess() {
        ProviderRegistry reg = new ProviderRegistry(
                List.of(provider("a")), ProviderStateStore.inMemory());
        String activeBefore = reg.active().provider().name();

        Provider newProvider = provider("deepseek");
        reg.addProvider(newProvider);

        assertTrue(reg.names().contains("deepseek"));
        assertTrue(reg.find("deepseek").isPresent());
        assertEquals(newProvider, reg.find("deepseek").get());
        assertEquals(activeBefore, reg.active().provider().name(),
                "active provider must not change");
    }

    @Test
    void addProviderDuplicateNameThrows() {
        ProviderRegistry reg = new ProviderRegistry(
                List.of(provider("a")), ProviderStateStore.inMemory());

        ProviderException ex = assertThrows(ProviderException.class,
                () -> reg.addProvider(provider("a")));
        assertTrue(ex.getMessage().contains("duplicate"));
    }

    @Test
    void addProviderReservedNameResetThrows() {
        ProviderRegistry reg = new ProviderRegistry(
                List.of(provider("a")), ProviderStateStore.inMemory());

        assertThrows(ProviderException.class, () -> reg.addProvider(provider("reset")));
        assertThrows(ProviderException.class, () -> reg.addProvider(provider("RESET")));
        assertThrows(ProviderException.class, () -> reg.addProvider(provider("Reset")));
    }

    @Test
    void removeProviderSuccess() {
        ProviderRegistry reg = new ProviderRegistry(
                List.of(provider("a"), provider("b")), ProviderStateStore.inMemory());
        reg.removeProvider("b");

        assertFalse(reg.find("b").isPresent());
        assertEquals(1, reg.names().size());
    }

    @Test
    void removeActiveProviderThrows() {
        ProviderRegistry reg = new ProviderRegistry(
                List.of(provider("a"), provider("b")), ProviderStateStore.inMemory());
        // "a" is the active provider by default

        ProviderException ex = assertThrows(ProviderException.class,
                () -> reg.removeProvider("a"));
        assertTrue(ex.getMessage().contains("active"));
    }

    @Test
    void removeLastProviderThrows() {
        ProviderRegistry reg = new ProviderRegistry(
                List.of(provider("a")), ProviderStateStore.inMemory());

        ProviderException ex = assertThrows(ProviderException.class,
                () -> reg.removeProvider("a"));
        assertTrue(ex.getMessage().contains("only provider"));
    }

    @Test
    void removeNonExistentNameIsNoOp() {
        ProviderRegistry reg = new ProviderRegistry(
                List.of(provider("a")), ProviderStateStore.inMemory());

        reg.removeProvider("nonexistent"); // should not throw
        assertEquals(1, reg.names().size());
    }
}
