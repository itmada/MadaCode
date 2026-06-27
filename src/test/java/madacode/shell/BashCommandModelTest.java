package madacode.shell;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BashCommandModelTest {

    @Test
    void parsesSegmentsAndRecognizesReadOnlyGit() {
        BashCommandModel model = BashCommandModel.parse("FOO=bar git -C ../repo status && rg -n needle src");

        assertEquals(2, model.segments().size());
        BashCommandModel.Segment git = model.segments().getFirst();
        assertEquals("git", git.commandName());
        assertEquals("status", git.gitSubcommand());
        assertTrue(git.isReadOnlyGit());
        assertFalse(git.isMutatingCommand());
        assertEquals("../repo", git.gitChangeDirectoryTarget());
        assertEquals(Path.of("/tmp/work/../repo").normalize(), git.gitWorkTree(Path.of("/tmp/work")));
        assertFalse(git.hasRedirection());
        assertTrue(model.isBasicReadOnly());
    }

    @Test
    void recognizesMutatingFormsAndStructuralTargets() {
        BashCommandModel sortModel = BashCommandModel.parse("sort -o build/out.txt data/values.txt");
        BashCommandModel.Segment sort = sortModel.segments().getFirst();
        assertEquals("sort", sort.commandName());
        assertTrue(sort.inPlaceMutation());
        assertTrue(sort.isMutatingCommand());
        assertTrue(sort.pathLikeArgs().contains("build/out.txt"));
        assertTrue(sort.pathLikeArgs().contains("data/values.txt"));

        BashCommandModel findModel = BashCommandModel.parse("find ./src -name '*.java' -exec rm {} +");
        BashCommandModel.Segment find = findModel.segments().getFirst();
        assertTrue(find.findMutation());
        assertTrue(find.isMutatingCommand());
        assertEquals("./src", find.pathLikeArgs().getFirst());

        BashCommandModel redirectionModel = BashCommandModel.parse("cat README.md > ../out.txt");
        BashCommandModel.Segment redirection = redirectionModel.segments().getFirst();
        assertTrue(redirection.hasRedirection());
        assertEquals(1, redirection.redirections().size());
        assertEquals("../out.txt", redirection.redirections().getFirst().target());
        assertFalse(redirection.isBasicReadOnlyCommand());
    }

    @Test
    void treatsHarmlessRedirectionsAsReadOnly() {
        // stderr discarded to the null device has no filesystem side effect
        assertTrue(BashCommandModel.parse("find src -type f 2>/dev/null | sort").isBasicReadOnly());
        // file-descriptor duplication is not a real-file write
        assertTrue(BashCommandModel.parse("grep -rn needle src 2>&1").isBasicReadOnly());
        // input redirection only reads
        assertTrue(BashCommandModel.parse("sort < data.txt").isBasicReadOnly());

        // writing an actual file is still a mutation
        BashCommandModel realFile = BashCommandModel.parse("cat README.md > out.txt");
        assertFalse(realFile.isBasicReadOnly());
        assertTrue(realFile.segments().getFirst().writesRealFile());
        assertFalse(BashCommandModel.parse("grep needle src 2>/dev/null")
                .segments().getFirst().writesRealFile());
    }

    @Test
    void tracksExpansionAndDirectoryTargetsConservatively() {
        BashCommandModel cdModel = BashCommandModel.parse("cd \"$HOME/project\"");
        BashCommandModel.Segment cd = cdModel.segments().getFirst();
        assertEquals("cd", cd.commandName());
        assertEquals("$HOME/project", cd.cdTarget());
        assertTrue(cd.hasUnresolvedExpansion());
        assertNull(cd.gitWorkTree(Path.of("/tmp/work")));

        BashCommandModel loopModel = BashCommandModel.parse("for f in *.java");
        BashCommandModel.Segment loop = loopModel.segments().getFirst();
        assertEquals("for", loop.commandName());
        assertFalse(loop.hasUnresolvedExpansion());
        assertFalse(loop.isMutatingCommand());
    }
}
