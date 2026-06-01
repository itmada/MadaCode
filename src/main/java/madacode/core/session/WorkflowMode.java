package madacode.core.session;

import java.util.Arrays;

public enum WorkflowMode {
    COMMON("common"),
    LONG_RUNNING("long-running");

    private final String persistedValue;

    WorkflowMode(String persistedValue) {
        this.persistedValue = persistedValue;
    }

    public String persistedValue() {
        return persistedValue;
    }

    public static WorkflowMode fromPersistedValue(String value) {
        return Arrays.stream(values())
                .filter(mode -> mode.persistedValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported workflowMode: " + value));
    }
}
