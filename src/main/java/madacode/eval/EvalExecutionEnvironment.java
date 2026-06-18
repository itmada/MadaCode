package madacode.eval;

import java.nio.file.Path;

/**
 * Isolation boundary for an eval case. Runner, workflow, and judge code depend on this
 * contract rather than assuming that a temporary directory is a security sandbox.
 */
public interface EvalExecutionEnvironment extends AutoCloseable {

    Path workspace();

    VerifyOutcome runVerify(RunBudget budget);

    IsolationLevel isolationLevel();

    default TrustProfile trustProfile() {
        return TrustProfile.forIsolation(isolationLevel());
    }

    @Override
    void close();

    enum IsolationLevel {
        /** Temporary working directory only; absolute paths and host processes remain reachable. */
        LOCAL_UNSAFE,
        /** Reserved for a container/VM backend with filesystem and process isolation. */
        CONTAINER
    }

    record TrustProfile(
            IsolationLevel isolationLevel,
            JudgeVisibility judgeVisibility,
            HostAccess hostAccess,
            NetworkAccess networkAccess,
            boolean trustedMeasurement) {

        static TrustProfile forIsolation(IsolationLevel isolationLevel) {
            return switch (isolationLevel) {
                case LOCAL_UNSAFE -> new TrustProfile(
                        isolationLevel,
                        JudgeVisibility.HOST_READABLE,
                        HostAccess.ALLOWED,
                        NetworkAccess.ALLOWED,
                        false);
                case CONTAINER -> new TrustProfile(
                        isolationLevel,
                        JudgeVisibility.HIDDEN,
                        HostAccess.BLOCKED,
                        NetworkAccess.BLOCKED,
                        true);
            };
        }
    }

    enum JudgeVisibility {
        HIDDEN,
        HOST_READABLE
    }

    enum HostAccess {
        BLOCKED,
        ALLOWED
    }

    enum NetworkAccess {
        BLOCKED,
        ALLOWED
    }

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
