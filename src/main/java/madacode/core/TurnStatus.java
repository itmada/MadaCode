package madacode.core;

public enum TurnStatus {
    PENDING, RUNNING, DONE, FAILED, CANCELED;

    public boolean isTerminal() {
        return this == DONE || this == FAILED || this == CANCELED;
    }
}
