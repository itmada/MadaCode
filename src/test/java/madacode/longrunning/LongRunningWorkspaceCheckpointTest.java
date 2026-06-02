package madacode.longrunning;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LongRunningWorkspaceCheckpointTest {

    @TempDir
    Path tempDir;

    @Test
    void capturesNonGitWorkspace() {
        LongRunningWorkspaceCheckpoint checkpoint = LongRunningWorkspaceCheckpoint.capture(tempDir);

        assertFalse(checkpoint.gitRepository());
        assertEquals(tempDir.toAbsolutePath().normalize(), checkpoint.projectDirectory());
    }

    @Test
    void capturesGitWorkspaceMetadata() throws Exception {
        assumeTrue(run(tempDir, "git", "--version") == 0);
        runRequired(tempDir, "git", "init");
        runRequired(tempDir, "git", "config", "user.email", "test@example.com");
        runRequired(tempDir, "git", "config", "user.name", "Test User");
        Files.writeString(tempDir.resolve("README.md"), "hello\n");
        runRequired(tempDir, "git", "add", "README.md");
        runRequired(tempDir, "git", "commit", "-m", "initial");
        Files.writeString(tempDir.resolve("dirty.txt"), "dirty\n");

        LongRunningWorkspaceCheckpoint checkpoint = LongRunningWorkspaceCheckpoint.capture(tempDir);

        assertTrue(checkpoint.gitRepository());
        assertEquals(tempDir.toRealPath(), checkpoint.gitRoot());
        assertTrue(checkpoint.head() != null && checkpoint.head().length() >= 7);
        assertTrue(checkpoint.dirty());
        assertTrue(checkpoint.statusShort().contains("dirty.txt"));
    }

    private static void runRequired(Path cwd, String... command) throws Exception {
        int exit = run(cwd, command);
        if (exit != 0) {
            throw new AssertionError("Command failed: " + String.join(" ", command));
        }
    }

    private static int run(Path cwd, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .start();
        process.waitFor();
        return process.exitValue();
    }
}
