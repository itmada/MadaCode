package madacode.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.governance.IsolationProfile;
import madacode.tool.BashTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunningWorkspacePermissionRuleTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final LongRunningWorkspacePermissionRule rule = new LongRunningWorkspacePermissionRule();
    private final BashTool bashTool = new BashTool();

    @Test
    void allowsUnresolvedExpansionWhenNoStaticScopeViolationIsConfirmed() {
        assertAllowed(
                "git commit -m \"fix $x\"",
                LongRunningWorkspacePermissionRule.SOURCE_UNRESOLVED_EXPANSION);
        assertAllowed("grep \"$p\" README.md");
        assertAllowed("for f in *.java");
    }

    @Test
    void stillDeniesConfirmedExternalWritesAndUnsafeDirectoryExpansion() {
        PermissionDecision externalWrite = evaluate("git add ../outside.txt");
        assertFalse(externalWrite.isAllowed());
        assertEquals(LongRunningWorkspacePermissionRule.SOURCE, externalWrite.source());
        assertEquals("Long-running worker bash cannot modify files outside the workspace.", externalWrite.reason());

        PermissionDecision changingDirWithExpansion = evaluate("cd \"$HOME/project\"");
        assertFalse(changingDirWithExpansion.isAllowed());
        assertEquals(LongRunningWorkspacePermissionRule.SOURCE, changingDirWithExpansion.source());
        assertEquals(
                "Long-running worker bash cannot use unresolved shell expansion to change directories.",
                changingDirWithExpansion.reason());
    }

    @Test
    void containerIsolationLetsHardBoundaryReplaceStaticWorkspaceScope() {
        ConversationSession session = workerSession();
        session.setIsolationProfile(IsolationProfile.container());

        assertTrue(rule.evaluate(bashTool, bashInput("git add ../outside.txt"), new ToolUseContext(tempDir, session))
                .isEmpty());
    }

    @Test
    void unresolvedMutatingExpansionIsAllowedWithAuditSourceWhenNoStaticViolationIsConfirmed() {
        PermissionDecision decision = evaluate("rm $TARGET");

        assertTrue(decision.isAllowed());
        assertEquals(LongRunningWorkspacePermissionRule.SOURCE_UNRESOLVED_EXPANSION, decision.source());
    }

    private void assertAllowed(String command) {
        assertAllowed(command, LongRunningWorkspacePermissionRule.SOURCE);
    }

    private void assertAllowed(String command, String source) {
        PermissionDecision decision = evaluate(command);
        assertTrue(decision.isAllowed(), () -> "Expected allowed bash command: " + command + " but got: " + decision.reason());
        assertEquals(source, decision.source());
    }

    private PermissionDecision evaluate(String command) {
        return rule.evaluate(bashTool, bashInput(command), context()).orElseThrow();
    }

    private ToolUseContext context() {
        return new ToolUseContext(tempDir, workerSession());
    }

    private ConversationSession workerSession() {
        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);
        session.setLongRunningWorkerSession(true);
        return session;
    }

    private ObjectNode bashInput(String command) {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", command);
        return input;
    }
}
