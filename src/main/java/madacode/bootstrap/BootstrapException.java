package madacode.bootstrap;

/**
 * Thrown when bootstrapping fails for a user-facing reason
 * (bad session id, ambiguous prefix, user chose exit, etc.).
 * The {@code exitCode} is consumed by {@code MadaAgentCLI.main}.
 */
public final class BootstrapException extends RuntimeException {

    private final int exitCode;

    public BootstrapException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
