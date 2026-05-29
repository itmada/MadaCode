package madacode.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BuiltInSkillsTest {

    private static SkillRegistry registry;

    @BeforeAll
    static void setUp() {
        SkillStateStore store = new SkillStateStore(
                Path.of(System.getProperty("user.home"), ".mada/skills.json"));
        registry = new SkillRegistry(store, new BundledSkillLoader());
        registry.reload();
    }

    @Test
    void loadsBundledSkills() {
        assertTrue(registry.all().size() >= 2,
                "Should load at least code-review and simplify");
    }

    @Test
    void findCodeReview() {
        Optional<Skill> s = registry.find("code-review");
        assertTrue(s.isPresent());
        assertEquals("code-review", s.get().name());
    }

    @Test
    void findSimplify() {
        Optional<Skill> s = registry.find("simplify");
        assertTrue(s.isPresent());
        assertEquals("simplify", s.get().name());
    }

    @Test
    void unknownSkillReturnsEmpty() {
        assertTrue(registry.find("nonexistent").isEmpty());
    }

    @Test
    void blankNameReturnsEmpty() {
        assertTrue(registry.find("").isEmpty());
        assertTrue(registry.find(null).isEmpty());
    }

    @Test
    void codeReviewIsInline() {
        var s = registry.find("code-review").orElseThrow();
        assertEquals("inline", s.mode());
    }

    @Test
    void codeReviewHasAllowedTools() {
        var s = registry.find("code-review").orElseThrow();
        assertTrue(s.allowedTools().contains("file_read"),
                "code-review should allow file_read, got: " + s.allowedTools());
    }

    @Test
    void codeReviewDisallowsWrite() {
        var s = registry.find("code-review").orElseThrow();
        assertTrue(s.disallowedTools().contains("write"),
                "code-review should disallow write, got: " + s.disallowedTools());
    }

    @Test
    void simplifyIsInline() {
        var s = registry.find("simplify").orElseThrow();
        assertEquals("inline", s.mode());
    }

    @Test
    void simplifyAllowsEdit() {
        var s = registry.find("simplify").orElseThrow();
        assertTrue(s.allowedTools().contains("edit"),
                "simplify should allow edit, got: " + s.allowedTools());
    }

    @Test
    void codeReviewHasBody() {
        var s = registry.find("code-review").orElseThrow();
        assertTrue(s.body().contains("Code Review"));
        assertTrue(s.body().contains("P1"));
    }

    @Test
    void simplifyHasBody() {
        var s = registry.find("simplify").orElseThrow();
        assertTrue(s.body().contains("Simplify"));
    }

    @Test
    void skillsHaveDescriptions() {
        for (Skill s : registry.enabled()) {
            assertNotNull(s.description());
            assertFalse(s.description().isBlank());
        }
    }

    @Test
    void skillsHaveWhenToUse() {
        for (Skill s : registry.enabled()) {
            assertNotNull(s.whenToUse());
            assertFalse(s.whenToUse().isBlank());
        }
    }

    @Test
    void skillsHaveBundleSource() {
        for (Skill s : registry.all()) {
            assertEquals(SkillSource.BUNDLED, s.source());
        }
    }
}
