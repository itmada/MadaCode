package madacode.events;

import java.time.Duration;

public interface AppEventPublisher extends AutoCloseable {
    void publish(AppEvent event);

    long nextSequence();

    void flush(Duration timeout);

    @Override
    void close();
}
