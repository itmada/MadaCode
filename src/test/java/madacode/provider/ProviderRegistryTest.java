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
}
