package madacode.cli;

import madacode.core.ConversationSession;
import madacode.core.Message;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtFileCompleterTest {

    @TempDir
    Path tempDir;

    @Test
    void expandsWorkspaceFileMention() throws Exception {
        Files.writeString(tempDir.resolve("note.txt"), "hello");

        String expanded = AtFileCompleter.expandMentions("read @note.txt", session());

        assertTrue(expanded.contains("<file path=\"note.txt\">"));
        assertTrue(expanded.contains("hello"));
    }

    @Test
    void supportsSlashPrefixedRelativeMention() throws Exception {
        Files.writeString(tempDir.resolve("note.txt"), "hello");

        String expanded = AtFileCompleter.expandMentions("read @/note.txt", session());

        assertTrue(expanded.contains("<file path=\"note.txt\">"));
    }

    @Test
    void refusesPathTraversal() {
        String input = "read @../../etc/passwd";

        assertEquals(input, AtFileCompleter.expandMentions(input, session()));
    }

    @Test
    void refusesGitDirectoryFiles() throws Exception {
        Path git = tempDir.resolve(".git");
        Files.createDirectories(git);
        Files.writeString(git.resolve("config"), "secret");
        String input = "read @.git/config";

        assertEquals(input, AtFileCompleter.expandMentions(input, session()));
    }

    @Test
    void refusesOversizedFiles() throws Exception {
        Files.writeString(tempDir.resolve("large.txt"), "x".repeat((int) AtFileCompleter.MAX_FILE_BYTES + 1));
        String input = "read @large.txt";

        assertEquals(input, AtFileCompleter.expandMentions(input, session()));
    }

    @Test
    void suggestionsAreLimitedAndSkipHiddenFiles() throws Exception {
        Files.writeString(tempDir.resolve(".secret"), "no");
        for (int i = 0; i < 12; i++) {
            Files.writeString(tempDir.resolve("file-" + i + ".txt"), "ok");
        }

        List<AtFileCompleter.Suggestion> suggestions =
                AtFileCompleter.suggestions(tempDir, "file", 8);

        assertEquals(8, suggestions.size());
        assertFalse(suggestions.stream().anyMatch(s -> s.relativePath().startsWith(".")));
    }

    @Test
    void suggestionSetExposesMentionRangeForInlineOverlay() throws Exception {
        Files.createDirectories(tempDir.resolve("src"));
        Files.writeString(tempDir.resolve("src/App.java"), "class App {}");

        Optional<AtFileCompleter.SuggestionSet> set = AtFileCompleter.suggestionSet(
                "read @src/A",
                "read @src/A".length(),
                tempDir,
                8);

        assertTrue(set.isPresent());
        assertEquals("read ".length(), set.get().start());
        assertEquals("read @src/A".length(), set.get().end());
        assertEquals(List.of(new AtFileCompleter.Suggestion("src/App.java", false)), set.get().suggestions());
    }

    private ConversationSession session() {
        return new ConversationSession(
                "s",
                Instant.now(),
                tempDir,
                List.of(Message.system("Init")));
    }
}
