package madacode.bootstrap;

import madacode.events.AppEvents;
import madacode.events.DefaultAppEventPublisher;
import madacode.events.sinks.AuditSink;
import madacode.storage.RuntimePaths;

final class EventsAssembly {

    private EventsAssembly() {
    }

    static EventsRuntime install(
            RuntimePaths paths,
            TerminalRuntime terminal,
            BootstrapResources resources) {
        ForegroundSessionTracker foreground = new ForegroundSessionTracker();
        DefaultAppEventPublisher publisher = resources.own(new DefaultAppEventPublisher(
                terminal.screen(),
                foreground,
                paths.workspacePermissionAuditFile()));
        AppEvents.install(publisher);
        return new EventsRuntime(publisher, foreground);
    }
}
