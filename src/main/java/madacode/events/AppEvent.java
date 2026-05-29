package madacode.events;

import java.time.Instant;

public sealed interface AppEvent
        permits UserVisibleEvent, DiagnosticEvent, AuditEvent, FatalEvent {
    Instant timestamp();
    long sequence();
    EventContext context();
}
