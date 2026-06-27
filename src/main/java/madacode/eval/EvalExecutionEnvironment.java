package madacode.eval;

import madacode.governance.EgressReport;
import madacode.governance.IsolationProfile;

import java.nio.file.Path;

/**
 * Isolation boundary for an eval case. Runner, workflow, and judge code depend on this
 * contract rather than assuming that a temporary directory is a security sandbox.
 */
public interface EvalExecutionEnvironment extends AutoCloseable {

    Path workspace();

    VerifyOutcome runVerify(RunBudget budget);

    IsolationProfile isolationProfile();

    /**
     * Network evidence produced by the execution boundary. LOCAL_UNSAFE explicitly reports
     * UNAVAILABLE rather than treating an empty event list as proof that no egress occurred.
     */
    default EgressReport egressReport() {
        return EgressReport.unavailable();
    }

    @Override
    void close();

    record VerifyOutcome(VerifyStatus status, int exitCode, String output) {
        public boolean passed() {
            return status == VerifyStatus.PASSED;
        }
    }

    enum VerifyStatus {
        PASSED,
        FAILED,
        ERROR,
        TIMED_OUT,
        INTERRUPTED
    }
}
