package madacode.execution;

import madacode.governance.EgressReport;
import madacode.governance.IsolationProfile;

import java.nio.file.Path;

public interface WorkerExecutionEnvironment extends AutoCloseable {

    Path workspace();

    IsolationProfile isolationProfile();

    EgressReport egressReport();

    @Override
    void close();
}
