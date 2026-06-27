package madacode.execution;

import madacode.governance.EgressReport;
import madacode.governance.IsolationProfile;

import java.nio.file.Path;

public final class HostProcessEnvironment implements WorkerExecutionEnvironment {

    private final Path workspace;

    public HostProcessEnvironment(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    @Override
    public Path workspace() {
        return workspace;
    }

    @Override
    public IsolationProfile isolationProfile() {
        return IsolationProfile.localUnsafe();
    }

    @Override
    public EgressReport egressReport() {
        return EgressReport.unavailable();
    }

    @Override
    public void close() {
    }
}
