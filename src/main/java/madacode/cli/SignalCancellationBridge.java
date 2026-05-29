package madacode.cli;


/**
 * Temporarily routes SIGINT to a {@link CancellationToken} while a turn is
 * running.
 *
 * <p>The handler is installed only around {@code QueryEngine.runTurn} and
 * restored immediately afterwards. This keeps prompt-level Ctrl+C semantics
 * owned by JLine, while still letting Ctrl+C cancel an active model request
 * or tool execution.
 *
 * <p>Implementation note: this uses {@code sun.misc.Signal}, which is
 * available in JDK 21 via the {@code jdk.unsupported} module. If signal
 * registration fails (sandbox, JNI restriction, or future JDK removal),
 * cancellation falls back to "no SIGINT support" — Ctrl+C will revert to
 * the runtime's default behaviour.
 */
public final class SignalCancellationBridge {

    private static final Registration NOOP = () -> {};

    /**
     * Installs a temporary SIGINT handler that runs {@code onCancel}. The
     * returned registration must be closed in a {@code finally} block to
     * restore the previous handler.
     */
    @SuppressWarnings({"removal", "restriction"})
    public Registration activate(Runnable onCancel) {
        try {
            sun.misc.Signal signal = new sun.misc.Signal("INT");
            sun.misc.SignalHandler previous = sun.misc.Signal.handle(
                    signal,
                    sig -> onCancel.run());
            return () -> restore(signal, previous);
        } catch (IllegalArgumentException | SecurityException | UnsatisfiedLinkError e) {
            // Signal API may be unavailable in restricted runtimes — degrade
            // gracefully so the rest of the agent still runs.
            return NOOP;
        }
    }

    @SuppressWarnings({"removal", "restriction"})
    private void restore(sun.misc.Signal signal, sun.misc.SignalHandler previous) {
        try {
            sun.misc.Signal.handle(signal, previous);
        } catch (IllegalArgumentException | SecurityException | UnsatisfiedLinkError ignored) {
            // Best-effort restoration only.
        }
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
