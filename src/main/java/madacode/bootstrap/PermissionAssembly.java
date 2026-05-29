package madacode.bootstrap;

import madacode.events.AppEvents;
import madacode.events.EventContext;
import madacode.events.UserVisibleEvent;
import madacode.permission.ApprovalResponse;
import madacode.permission.DefaultPermissionGate;
import madacode.permission.PermissionGate;
import madacode.permission.UserApprovalPrompt;

final class PermissionAssembly {

    private PermissionAssembly() {
    }

    static PermissionGate create(TerminalRuntime terminal) {
        if (terminal.interactive()) {
            return new DefaultPermissionGate(terminal.approval());
        }
        return new DefaultPermissionGate((UserApprovalPrompt) (tool, input) -> {
            AppEvents.publisher().publish(UserVisibleEvent.error(
                    EventContext.bootstrap("Permission"),
                    "tool " + (tool != null ? tool.name() : "unknown")
                            + " denied: non-interactive mode"));
            return ApprovalResponse.DENY;
        });
    }
}
