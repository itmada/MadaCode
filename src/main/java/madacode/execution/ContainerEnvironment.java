package madacode.execution;

import madacode.governance.EgressReport;
import madacode.governance.IsolationProfile;

import java.nio.file.Path;

/**
 * Placeholder execution environment for a future provisioned container backend.
 */
public final class ContainerEnvironment implements WorkerExecutionEnvironment {

    private final Path workspace;

    public ContainerEnvironment(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
    }

    @Override
    public Path workspace() {
        return workspace;
    }

    @Override
    public IsolationProfile isolationProfile() {
        return IsolationProfile.container();
    }

    @Override
    public EgressReport egressReport() {
        throw new UnsupportedOperationException("container backend not yet provisioned");
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("container backend not yet provisioned");
    }
}
