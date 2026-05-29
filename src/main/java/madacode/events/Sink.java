package madacode.events;

import java.time.Duration;

public interface Sink<E extends AppEvent> {
    void accept(E event);

    default void flush(Duration timeout) {
    }

    default void close() {
    }
}
