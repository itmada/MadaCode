package madacode.bootstrap;

import madacode.permission.DefaultPermissionGate;
import madacode.permission.PermissionGate;

import java.nio.file.Path;
import java.util.List;

final class PermissionAssembly {

    private PermissionAssembly() {
    }

    static PermissionGate create(EnvironmentRuntime environment, TerminalRuntime terminal) {
        List<Path> trustedRoots = List.of(environment.paths().globalBlobsDir());
        return new DefaultPermissionGate(
                terminal.approval(),
                trustedRoots,
                environment.diagnosticEvents());
    }
}
