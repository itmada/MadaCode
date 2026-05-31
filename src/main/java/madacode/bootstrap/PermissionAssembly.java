package madacode.bootstrap;

import madacode.permission.DefaultPermissionGate;
import madacode.permission.PermissionGate;
import madacode.tool.MadaPaths;

import java.nio.file.Path;
import java.util.List;

final class PermissionAssembly {

    private PermissionAssembly() {
    }

    static PermissionGate create(TerminalRuntime terminal) {
        List<Path> trustedRoots = List.of(MadaPaths.blobsDir());
        return new DefaultPermissionGate(terminal.approval(), trustedRoots);
    }
}
