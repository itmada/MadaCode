package madacode.events.sinks;

import madacode.events.DiagnosticEvent;
import madacode.events.Sink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class DiagnosticSink implements Sink<DiagnosticEvent> {

    private static final Logger LOG = LoggerFactory.getLogger("mada.diagnostic");

    @Override
    public void accept(DiagnosticEvent event) {
        String sessionId = dash(event.context().sessionId());
        String turnId = dash(event.context().turnId());
        try (MDC.MDCCloseable ignoredSession = MDC.putCloseable("sessionId", sessionId);
             MDC.MDCCloseable ignoredTurn = MDC.putCloseable("turnId", turnId)) {
            switch (event.severity()) {
                case DEBUG -> LOG.debug("[{}] seq={} {}",
                        event.context().source(), event.sequence(), event.message(), event.error());
                case INFO -> LOG.info("[{}] seq={} {}",
                        event.context().source(), event.sequence(), event.message(), event.error());
                case WARN -> LOG.warn("[{}] seq={} {}",
                        event.context().source(), event.sequence(), event.message(), event.error());
                case ERROR -> LOG.error("[{}] seq={} {}",
                        event.context().source(), event.sequence(), event.message(), event.error());
            }
        }
    }

    private static String dash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
