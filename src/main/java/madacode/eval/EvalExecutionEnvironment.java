package madacode.eval;

import java.nio.file.Path;
import java.util.List;

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

    /**
     * Network evidence produced by the execution boundary. LOCAL_UNSAFE explicitly reports
     * UNAVAILABLE rather than treating an empty event list as proof that no egress occurred.
     */
    default EgressReport egressReport() {
        return EgressReport.unavailable();
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

    record EgressReport(EgressObservation observation, List<EgressEvent> events) {
        public EgressReport {
            observation = observation == null ? EgressObservation.UNAVAILABLE : observation;
            events = events == null ? List.of() : List.copyOf(events);
        }

        public static EgressReport unavailable() {
            return new EgressReport(EgressObservation.UNAVAILABLE, List.of());
        }
    }

    enum EgressObservation {
        UNAVAILABLE,
        OBSERVED
    }

    record EgressEvent(String destination, boolean blocked, String detail) {
        public EgressEvent {
            destination = destination == null ? "" : destination;
            detail = detail == null ? "" : detail;
        }
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
