package madacode.longrunning;

import madacode.core.model.Message;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.permission.PermissionMode;

import java.nio.file.Path;

public final class LongRunningControlSessionFactory {

    public LongRunningControlSessionFactory() {
    }

    public ConversationSession create(Path workingDirectory) {
        ConversationSession session = new ConversationSession(workingDirectory);
        session.replaceMessages(java.util.List.of(Message.system(
                "Long-running draft is ready. Please describe the task requirements in detail, including the goal, scope, constraints, expected result, and any steps that must or must not be taken. Workers will start only after you confirm RUNNING.")));
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setPlanMode(false);
        session.setPermissionMode(PermissionMode.BYPASS);
        session.setLongRunningStage(LongRunningStage.DRAFT);
        return session;
    }
}
