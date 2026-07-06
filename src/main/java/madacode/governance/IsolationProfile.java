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
        ALLOWED,
        PROXIED
    }

    public static IsolationProfile localUnsafe() {
        return forLevel(IsolationLevel.LOCAL_UNSAFE);
    }

    public static IsolationProfile container() {
        return forLevel(IsolationLevel.CONTAINER);
    }

    /**
     * Phase-1 eval container profile: the agent and host are isolated enough to hide
     * judge assets and reap background processes, but network egress is still open
     * and unobserved. It must not be reported as a trusted measurement.
     */
    public static IsolationProfile containerOpenNetwork() {
        return new IsolationProfile(
                IsolationLevel.CONTAINER,
                JudgeVisibility.HIDDEN,
                HostAccess.BLOCKED,
                NetworkAccess.ALLOWED,
                false);
    }

    /**
     * Phase-2 eval container profile: the agent has no direct external route and
     * provider egress is mediated by an allowlist proxy that records observations.
     */
    public static IsolationProfile containerProxiedNetwork() {
        return new IsolationProfile(
                IsolationLevel.CONTAINER,
                JudgeVisibility.HIDDEN,
                HostAccess.BLOCKED,
                NetworkAccess.PROXIED,
                true);
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
