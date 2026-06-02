package madacode.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification tests for long-running task-state shell protection.
 *
 * <p>The permission boundary is intentionally conservative: bash must not
 * directly access source-of-truth state files. Reads should use read/search
 * tools, and writes must go through {@code longrun_task_update}.
 */
class FilesystemScopeBashBypassTest {

    @TempDir
    Path tempDir;

    @Test
    void findExecRmShouldBeDenied() {
        Path taskDir = tempDir.resolve(".mada/long-running/task-1");
        taskDir.toFile().mkdirs();

        String command = "find " + tempDir + "/.mada/long-running -name \"task.json\" -exec rm {} \\;";
        boolean denied = FilesystemScope.isProtectedLongRunningTaskStateShellAccess(command, tempDir);
        assertTrue(denied);
    }

    @Test
    void findDeleteShouldBeDenied() {
        Path taskDir = tempDir.resolve(".mada/long-running/task-1");
        taskDir.toFile().mkdirs();

        String command = "find " + tempDir + "/.mada/long-running -name \"task.json\" -delete";
        boolean denied = FilesystemScope.isProtectedLongRunningTaskStateShellAccess(command, tempDir);
        assertTrue(denied);
    }

    @Test
    void sortOShouldBeDenied() {
        Path taskDir = tempDir.resolve(".mada/long-running/task-1");
        taskDir.toFile().mkdirs();

        String command = "sort " + tempDir + "/.mada/long-running/task-1/task.json -o "
                + tempDir + "/.mada/long-running/task-1/task.json";
        boolean denied = FilesystemScope.isProtectedLongRunningTaskStateShellAccess(command, tempDir);
        assertTrue(denied);
    }

    @Test
    void catOfTaskJsonShouldBeDenied() {
        Path taskDir = tempDir.resolve(".mada/long-running/task-1");
        taskDir.toFile().mkdirs();

        String command = "cat " + tempDir + "/.mada/long-running/task-1/task.json";
        boolean denied = FilesystemScope.isProtectedLongRunningTaskStateShellAccess(command, tempDir);
        assertTrue(denied);
    }

    @Test
    void globbedJsonStateFileAccessShouldBeDenied() {
        String command = "rm .mada/long-running/task-1/*.json";
        boolean denied = FilesystemScope.isProtectedLongRunningTaskStateShellAccess(command, tempDir);
        assertTrue(denied);
    }

    @Test
    void splitPathReferenceShouldBeDenied() {
        String command = "cat .mada/long-running/task-1/task.json";
        boolean denied = FilesystemScope.isProtectedLongRunningTaskStateShellAccess(command, tempDir);
        assertTrue(denied);
    }

    @Test
    void lsLongRunningDirIsCorrectlyAllowed() {
        String command = "ls .mada/long-running";
        boolean denied = FilesystemScope.isProtectedLongRunningTaskStateShellAccess(command, tempDir);
        assertFalse(denied);
    }

    @Test
    void lsLongRunningTaskDirIsCorrectlyAllowed() {
        String command = "ls -la .mada/long-running/task-1";
        boolean denied = FilesystemScope.isProtectedLongRunningTaskStateShellAccess(command, tempDir);
        assertFalse(denied);
    }

    @Test
    void lsConcreteTaskJsonIsDenied() {
        String command = "ls .mada/long-running/task-1/task.json";
        boolean denied = FilesystemScope.isProtectedLongRunningTaskStateShellAccess(command, tempDir);
        assertTrue(denied);
    }

    @Test
    void unrelatedCommandIsAllowedByThisRule() {
        String command = "cat README.md";
        boolean denied = FilesystemScope.isProtectedLongRunningTaskStateShellAccess(command, tempDir);
        assertFalse(denied);
    }
}
