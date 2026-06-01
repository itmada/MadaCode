package madacode.core.session;

public enum LongRunningStage {
    WAITING_FOR_TASK,
    PLANNING,
    WAITING_FOR_APPROVAL,
    INITIALIZING,
    EXECUTING,
    COMPLETED,
    CANCELLED
}
