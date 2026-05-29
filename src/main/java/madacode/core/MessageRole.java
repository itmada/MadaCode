package madacode.core;

public enum MessageRole {
    SYSTEM,
    USER,
    ASSISTANT;

    public boolean isUserVisible() {
        return this == USER || this == ASSISTANT;
    }
}
