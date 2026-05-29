package madacode.bootstrap;

import madacode.events.AppEvents;
import madacode.events.DefaultAppEventPublisher;
import madacode.events.sinks.AuditSink;

final class EventsAssembly {

    private EventsAssembly() {
    }

    static EventsRuntime install(TerminalRuntime terminal, BootstrapResources resources) {
        ForegroundSessionTracker foreground = new ForegroundSessionTracker();
        DefaultAppEventPublisher publisher = resources.own(new DefaultAppEventPublisher(
                terminal.screen(),
                foreground,
                AuditSink.defaultPath()));
        AppEvents.install(publisher);
        return new EventsRuntime(publisher, foreground);
    }
}
