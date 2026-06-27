package madacode.governance;

import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.permission.PermissionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityProfilesTest {

    @TempDir
    Path tempDir;

    @Test
    void mainSessionCapabilityProfileTracksPermissionModePosture() {
        ConversationSession session = new ConversationSession(tempDir);
        session.setPermissionMode(PermissionMode.EDIT);

        CapabilityProfile profile = session.capabilityProfile();

        assertEquals("main", profile.id());
        assertEquals(ApprovalPosture.editInteractive(), profile.approvalPosture());
        assertTrue(profile.toolCapability().allows("web_fetch"));
    }

    @Test
    void workerSessionCapabilityProfileUsesWorkerToolSet() {
        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);
        session.setLongRunningWorkerSession(true);
        session.setIsolationProfile(IsolationProfile.container());

        CapabilityProfile profile = session.capabilityProfile();

        assertEquals("longrun-worker", profile.id());
        assertEquals(ApprovalPosture.longRunningWorker(), profile.approvalPosture());
        assertEquals(CapabilityProfiles.LONG_RUNNING_WORKER_TOOLS, profile.toolCapability().allowedTools());
        assertEquals(IsolationProfile.container(), profile.isolationProfile());
    }

    @Test
    void workerSessionCapabilityProfileBecomesEmptyAfterLeavingRunning() {
        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);
        session.setLongRunningWorkerSession(true);
        session.setLongRunningStage(LongRunningStage.COMPLETED);

        CapabilityProfile profile = session.capabilityProfile();

        assertEquals("longrun-worker", profile.id());
        assertTrue(profile.toolCapability().allowedTools().isEmpty());
    }
}
