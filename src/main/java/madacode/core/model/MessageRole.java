package madacode.core.model;

public enum MessageRole {
    SYSTEM,
    USER,
    ASSISTANT;

    public boolean isUserVisible() {
        return this == USER || this == ASSISTANT;
    }
}
