package madacode.longrunning;

import madacode.core.model.Message;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.permission.PermissionMode;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Creates a fresh long-running control session and initializes an empty task shell.
 */
public final class LongRunningSessionBootstrap {

    private LongRunningSessionBootstrap() {
    }

    public static ConversationSession createFreshControlSession(Path workingDirectory) {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        ConversationSession session = new ConversationSession(workingDirectory);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setPermissionMode(PermissionMode.BYPASS);
        session.setPlanMode(false);
        session.setLongRunningStage(LongRunningStage.DRAFT);
        session.setLongRunningReason("fresh control session");
        session.setExecutionStarted(false);

        LongRunningTaskInitializer initializer = new LongRunningTaskInitializer(
                new LongRunningTaskStore(workingDirectory),
                LongRunningTaskInitializer.TaskIdGenerator::defaultNewTaskId);
        initializer.ensureDraftTaskShell(session);

        session.replaceMessages(java.util.List.of(Message.system(
                "[long-running mode entered] Fresh control session initialized in DRAFT. "
                        + "A task shell exists on disk; discuss scope and plan here before execution begins.")));
        return session;
    }
}
