package madacode.agent;

import madacode.permission.PermissionMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentRegistryTest {

    private static AgentDefinition def(String name) {
        return new AgentDefinition(name, "desc", "when", "prompt body",
                Set.of(), Set.of(), 5, 10, PermissionMode.ACCEPT_EDITS);
    }

    @Test
    void emptyBeforeReload() {
        AgentRegistry registry = new AgentRegistry(() -> List.of(def("a")));
        assertTrue(registry.all().isEmpty());
        assertTrue(registry.findByType("a").isEmpty());
    }

    @Test
    void loadedFactoryReloads() {
        AgentRegistry registry = AgentRegistry.loaded(() -> List.of(def("a")));
        assertEquals(1, registry.all().size());
        assertTrue(registry.findByType("a").isPresent());
    }

    @Test
    void lastLoaderWinsOnNameCollision() {
        AgentDefinition v1 = new AgentDefinition("dup", "v1", "", "body",
                Set.of(), Set.of(), 5, 10, PermissionMode.ACCEPT_EDITS);
        AgentDefinition v2 = new AgentDefinition("dup", "v2", "", "body",
                Set.of(), Set.of(), 5, 10, PermissionMode.ACCEPT_EDITS);

        AgentRegistry registry = AgentRegistry.loaded(() -> List.of(v1), () -> List.of(v2));

        assertEquals(1, registry.all().size());
        assertEquals("v2", registry.findByType("dup").get().description());
    }

    @Test
    void findByTypeIsCaseInsensitive() {
        AgentRegistry registry = AgentRegistry.loaded(() -> List.of(def("Explorer")));
        assertTrue(registry.findByType("explorer").isPresent());
        assertTrue(registry.findByType("EXPLORER").isPresent());
        assertTrue(registry.findByType("eXpLoReR").isPresent());
    }

    @Test
    void findByTypeNullOrBlankReturnsEmpty() {
        AgentRegistry registry = AgentRegistry.loaded(() -> List.of(def("a")));
        assertTrue(registry.findByType(null).isEmpty());
        assertTrue(registry.findByType("").isEmpty());
        assertTrue(registry.findByType("   ").isEmpty());
    }

    @Test
    void reloadClearsOldState() {
        List<AgentDefinition> source = new java.util.ArrayList<>();
        source.add(def("a"));
        AgentRegistry registry = AgentRegistry.loaded(() -> List.copyOf(source));
        assertEquals(1, registry.all().size());

        source.clear();
        source.add(def("b"));
        registry.reload();

        assertEquals(1, registry.all().size());
        assertTrue(registry.findByType("a").isEmpty());
        assertTrue(registry.findByType("b").isPresent());
    }
}
