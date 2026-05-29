package madacode.provider;

import java.util.Objects;

public record Model(String name, int contextWindow) {
    public static final int DEFAULT_CONTEXT_WINDOW = 256_000;
    public static final int MIN_CONTEXT_WINDOW = 1_000;
    public static final int MAX_CONTEXT_WINDOW = 1_000_000;

    public Model {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("model name must not be blank");
        if (contextWindow < MIN_CONTEXT_WINDOW || contextWindow > MAX_CONTEXT_WINDOW) {
            throw new IllegalArgumentException(
                    "contextWindow " + contextWindow + " out of range ["
                            + MIN_CONTEXT_WINDOW + ", " + MAX_CONTEXT_WINDOW + "]");
        }
    }
}
