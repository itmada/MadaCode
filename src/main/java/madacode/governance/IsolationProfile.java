package madacode.governance;

/**
 * Real execution boundary for a runtime context. LOCAL_UNSAFE means only a
 * workspace convention exists; host process and network access remain reachable.
 */
public record IsolationProfile(
        IsolationLevel level,
        JudgeVisibility judgeVisibility,
        HostAccess hostAccess,
        NetworkAccess networkAccess,
        boolean trustedMeasurement) {

    public enum IsolationLevel {
        LOCAL_UNSAFE,
        CONTAINER
    }

    public enum JudgeVisibility {
        HIDDEN,
        HOST_READABLE
    }

    public enum HostAccess {
        BLOCKED,
        ALLOWED
    }

    public enum NetworkAccess {
        BLOCKED,
        ALLOWED
    }

    public static IsolationProfile localUnsafe() {
        return forLevel(IsolationLevel.LOCAL_UNSAFE);
    }

    public static IsolationProfile container() {
        return forLevel(IsolationLevel.CONTAINER);
    }

    public static IsolationProfile forLevel(IsolationLevel level) {
        return switch (level) {
            case LOCAL_UNSAFE -> new IsolationProfile(
                    level,
                    JudgeVisibility.HOST_READABLE,
                    HostAccess.ALLOWED,
                    NetworkAccess.ALLOWED,
                    false);
            case CONTAINER -> new IsolationProfile(
                    level,
                    JudgeVisibility.HIDDEN,
                    HostAccess.BLOCKED,
                    NetworkAccess.BLOCKED,
                    true);
        };
    }
}
