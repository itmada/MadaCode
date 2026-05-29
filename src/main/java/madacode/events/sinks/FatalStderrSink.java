package madacode.events.sinks;

import madacode.events.FatalEvent;
import madacode.events.Sink;

import java.io.PrintStream;
import java.util.Objects;

public class FatalStderrSink implements Sink<FatalEvent> {

    private final PrintStream err;

    public FatalStderrSink(PrintStream err) {
        this.err = Objects.requireNonNull(err, "err");
    }

    @Override
    public void accept(FatalEvent event) {
        err.println("[FATAL] " + event.message());
        if (event.error() != null) {
            event.error().printStackTrace(err);
        }
        err.flush();
    }
}
