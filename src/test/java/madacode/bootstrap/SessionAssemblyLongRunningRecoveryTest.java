package madacode.bootstrap;

import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.longrunning.CreateTaskRequest;
import madacode.longrunning.FeatureItem;
import madacode.longrunning.LongRunningTaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionAssemblyLongRunningRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void runningControlSessionRecoversToInterruptOnStartup() {
        Path workspace = tempDir.resolve("workspace");
        ConversationSession session = new ConversationSession(workspace);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.RUNNING);
        session.setLongRunningTaskId("task-recover");
        LongRunningTaskStore store = new LongRunningTaskStore(workspace);
        store.createTask(new CreateTaskRequest(
                "task-recover", "Recover", "DRAFT", null, session.sessionId(), "plan"));
        store.writeInitialFeatureList("task-recover", List.of(
                new FeatureItem("feature-a", "core", "high", "Feature A", List.of(), List.of("verify"), false)));
        store.markTaskExecuting("task-recover");

        SessionAssembly.recoverLongRunningSession(session);

        assertEquals(LongRunningStage.INTERRUPT, session.longRunningStage());
        assertEquals("user_interrupted", session.longRunningReason());
        assertEquals("INTERRUPT", store.loadTask("task-recover").status());
        assertEquals("user_interrupted", store.loadTask("task-recover").reason());
    }
}
