package madacode.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilesystemScopeTest {

    @Test
    void withinRoots_absolutePathInsideWorkingDir(@TempDir Path workingDir) {
        Path file = workingDir.resolve("src/Main.java");
        assertTrue(FilesystemScope.withinRoots(
                file.toString(), workingDir, List.of()));
    }

    @Test
    void withinRoots_relativePathInsideWorkingDir(@TempDir Path workingDir) {
        assertTrue(FilesystemScope.withinRoots(
                "src/Main.java", workingDir, List.of()));
    }

    @Test
    void withinRoots_parentTraversalInsideWorkingDir(@TempDir Path workingDir) throws IOException {
        Files.createDirectories(workingDir.resolve("sub"));
        assertTrue(FilesystemScope.withinRoots(
                "sub/../other.txt", workingDir, List.of()));
    }

    @Test
    void withinRoots_parentTraversalOutsideWorkingDir(@TempDir Path workingDir) {
        assertFalse(FilesystemScope.withinRoots(
                "../outside.txt", workingDir, List.of()));
    }

    @Test
    void withinRoots_absolutePathOutsideWorkingDir(@TempDir Path workingDir, @TempDir Path otherDir) {
        assertFalse(FilesystemScope.withinRoots(
                otherDir.resolve("file.txt").toString(), workingDir, List.of()));
    }

    @Test
    void withinRoots_symlinkEscapeBlocked(@TempDir Path workingDir) throws IOException {
        Path outside = Files.createTempFile("outside-escape", ".txt");
        Path link = workingDir.resolve("link.txt");
        try {
            Files.createSymbolicLink(link, outside);
            assertFalse(FilesystemScope.withinRoots(
                    "link.txt", workingDir, List.of()));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void withinRoots_workingDirIsSymlinkResolvesRealPath(@TempDir Path realRoot) throws IOException {
        // The working directory itself is a symlink; a path given by its real
        // location must still be recognised as inside (regression for the
        // resolved-comparison fix).
        Path linkParent = Files.createTempDirectory("wd-link-parent");
        Path workingDirLink = linkParent.resolve("workdir");
        try {
            Files.createSymbolicLink(workingDirLink, realRoot);
            Path realFile = realRoot.resolve("file.txt");
            Files.writeString(realFile, "x");
            assertTrue(FilesystemScope.withinRoots(
                    realFile.toString(), workingDirLink, List.of()));
        } finally {
            Files.deleteIfExists(workingDirLink);
            Files.deleteIfExists(linkParent);
        }
    }

    @Test
    void withinRoots_emptyPathIsWorkingDir(@TempDir Path workingDir) {
        assertTrue(FilesystemScope.withinRoots("", workingDir, List.of()));
        assertTrue(FilesystemScope.withinRoots(null, workingDir, List.of()));
    }

    @Test
    void withinRoots_trustedRootAllowsAccess(@TempDir Path workingDir, @TempDir Path blobsDir) throws IOException {
        Path blob = blobsDir.resolve("data.bin");
        Files.writeString(blob, "blob");

        assertTrue(FilesystemScope.withinRoots(
                blob.toString(), workingDir, List.of(blobsDir)));
    }

    @Test
    void withinRoots_pathNotInAnyRoot(@TempDir Path workingDir, @TempDir Path blobsDir, @TempDir Path otherDir) {
        assertFalse(FilesystemScope.withinRoots(
                otherDir.resolve("file.txt").toString(), workingDir, List.of(blobsDir)));
    }

    @Test
    void isDangerousEditTarget_bashrc() {
        assertTrue(FilesystemScope.isDangerousEditTarget(
                "/home/user/.bashrc", Path.of("/project")));
    }

    @Test
    void isDangerousEditTarget_zshrc() {
        assertTrue(FilesystemScope.isDangerousEditTarget(
                "/home/user/.zshrc", Path.of("/project")));
    }

    @Test
    void isDangerousEditTarget_profile() {
        assertTrue(FilesystemScope.isDangerousEditTarget(
                "/home/user/.profile", Path.of("/project")));
    }

    @Test
    void isDangerousEditTarget_gitconfig() {
        assertTrue(FilesystemScope.isDangerousEditTarget(
                "/home/user/.gitconfig", Path.of("/project")));
    }

    @Test
    void isDangerousEditTarget_mcpJson() {
        assertTrue(FilesystemScope.isDangerousEditTarget(
                "/home/user/.mcp.json", Path.of("/project")));
    }

    @Test
    void isDangerousEditTarget_claudeJson() {
        assertTrue(FilesystemScope.isDangerousEditTarget(
                "/home/user/.claude.json", Path.of("/project")));
    }

    @Test
    void isDangerousEditTarget_gitDirectory() {
        assertTrue(FilesystemScope.isDangerousEditTarget(
                "/project/.git/hooks/pre-commit", Path.of("/project")));
    }

    @Test
    void isDangerousEditTarget_sshDirectory() {
        assertTrue(FilesystemScope.isDangerousEditTarget(
                "/home/user/.ssh/authorized_keys", Path.of("/project")));
    }

    @Test
    void isDangerousEditTarget_vscodeDirectory() {
        assertTrue(FilesystemScope.isDangerousEditTarget(
                "/project/.vscode/settings.json", Path.of("/project")));
    }

    @Test
    void isDangerousEditTarget_safePath() {
        assertFalse(FilesystemScope.isDangerousEditTarget(
                "/project/src/Main.java", Path.of("/project")));
    }

    @Test
    void isDangerousEditTarget_caseInsensitive() {
        assertTrue(FilesystemScope.isDangerousEditTarget(
                "/home/user/.BASHRC", Path.of("/project")));
        assertTrue(FilesystemScope.isDangerousEditTarget(
                "/project/.GIT/config", Path.of("/project")));
    }

    @Test
    void isDangerousEditTarget_relativePath(@TempDir Path workingDir) {
        assertTrue(FilesystemScope.isDangerousEditTarget(
                ".bashrc", workingDir));
    }

    @Test
    void isDangerousEditTarget_emptyPath() {
        assertFalse(FilesystemScope.isDangerousEditTarget("", Path.of("/project")));
        assertFalse(FilesystemScope.isDangerousEditTarget(null, Path.of("/project")));
    }

    @Test
    void isDangerousEditTarget_ripgreprc() {
        assertTrue(FilesystemScope.isDangerousEditTarget(
                "/home/user/.ripgreprc", Path.of("/project")));
    }

    @Test
    void isDangerousEditTarget_bashProfile() {
        assertTrue(FilesystemScope.isDangerousEditTarget(
                "/home/user/.bash_profile", Path.of("/project")));
    }

    @Test
    void isDangerousEditTarget_zprofile() {
        assertTrue(FilesystemScope.isDangerousEditTarget(
                "/home/user/.zprofile", Path.of("/project")));
    }

    @Test
    void isDangerousEditTarget_gitmodules() {
        assertTrue(FilesystemScope.isDangerousEditTarget(
                "/project/.gitmodules", Path.of("/project")));
    }

    @Test
    void isDangerousEditTarget_ideaDirectory() {
        assertTrue(FilesystemScope.isDangerousEditTarget(
                "/project/.idea/vcs.xml", Path.of("/project")));
    }

    @Test
    void isDangerousEditTarget_claudeDirectory() {
        assertTrue(FilesystemScope.isDangerousEditTarget(
                "/project/.claude/settings.json", Path.of("/project")));
    }

    @Test
    void isDangerousEditTarget_symlinkWithInnocuousNameResolvesToDangerous(@TempDir Path workingDir) throws IOException {
        // An innocuously-named symlink that points at a dangerous file must be
        // flagged: a write would follow the link and modify the real target
        // (regression for the symlink-resolved danger check).
        Path realDanger = Files.createTempDirectory("danger");
        Path bashrc = realDanger.resolve(".bashrc");
        Files.writeString(bashrc, "x");
        Path link = workingDir.resolve("innocent.txt");
        try {
            Files.createSymbolicLink(link, bashrc);
            assertTrue(FilesystemScope.isDangerousEditTarget(link.toString(), workingDir));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(bashrc);
            Files.deleteIfExists(realDanger);
        }
    }
}