package madacode.provider;

import java.util.Objects;

public record ActiveState(Provider provider, Model currentModel) {
    public ActiveState {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(currentModel, "currentModel");
    }
}
