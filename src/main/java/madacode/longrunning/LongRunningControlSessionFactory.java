package madacode.longrunning;

import madacode.core.model.Message;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.permission.PermissionMode;

import java.nio.file.Path;
import java.util.Objects;

public final class LongRunningControlSessionFactory {

    private final LongRunningController.TaskStoreFactory taskStoreFactory;
    private final LongRunningTaskInitializer.TaskIdGenerator taskIdGenerator;

    public LongRunningControlSessionFactory() {
        this(LongRunningTaskStore::new, LongRunningTaskInitializer.TaskIdGenerator::defaultNewTaskId);
    }

    public LongRunningControlSessionFactory(
            LongRunningController.TaskStoreFactory taskStoreFactory,
            LongRunningTaskInitializer.TaskIdGenerator taskIdGenerator) {
        this.taskStoreFactory = Objects.requireNonNull(taskStoreFactory, "taskStoreFactory");
        this.taskIdGenerator = Objects.requireNonNull(taskIdGenerator, "taskIdGenerator");
    }

    public ConversationSession create(Path workingDirectory) {
        ConversationSession session = new ConversationSession(workingDirectory);
        session.replaceMessages(java.util.List.of(Message.system(
                "[long-running control session] Fresh control session created. "
                        + "Stage is DRAFT; task shell is initialized; discuss the task, maintain the draft with "
                        + "longrun_plan_update, and request RUNNING only after the user is ready.")));
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setPlanMode(false);
        session.setPermissionMode(PermissionMode.BYPASS);
        session.setLongRunningStage(LongRunningStage.DRAFT);

        LongRunningTaskInitializer initializer =
                new LongRunningTaskInitializer(taskStoreFactory.create(session.workingDirectory()), taskIdGenerator);
        initializer.ensurePlanningTask(session, "");
        return session;
    }
}
