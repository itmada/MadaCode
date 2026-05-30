package madacode.bootstrap;

import madacode.events.AppEvents;
import madacode.events.EventContext;
import madacode.events.UserVisibleEvent;
import madacode.permission.ApprovalResponse;
import madacode.permission.DefaultPermissionGate;
import madacode.permission.PermissionGate;
import madacode.permission.UserApprovalPrompt;
import madacode.tool.MadaPaths;

import java.nio.file.Path;
import java.util.List;

final class PermissionAssembly {

    private PermissionAssembly() {
    }

    static PermissionGate create(TerminalRuntime terminal) {
        List<Path> trustedRoots = List.of(MadaPaths.blobsDir());
        if (terminal.interactive()) {
            return new DefaultPermissionGate(terminal.approval(), trustedRoots);
        }
        return new DefaultPermissionGate((UserApprovalPrompt) (tool, input) -> {
            AppEvents.publisher().publish(UserVisibleEvent.error(
                    EventContext.bootstrap("Permission"),
                    "tool " + (tool != null ? tool.name() : "unknown")
                            + " denied: non-interactive mode"));
            return ApprovalResponse.DENY;
        }, trustedRoots);
    }
}
