package madacode.cli.slash;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashCommandRegistryTest {

    @Test
    void doesNotExposeReplayAllAfterSessionsReplayTheirTranscriptOnRestore() {
        SlashCommandRegistry registry = SlashCommandRegistry.create(null);

        assertTrue(registry.find("replay-all").isEmpty());
    }
}
