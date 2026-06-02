package madacode.longrunning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Captures the workspace state at the start of a long-running task.
 */
public record LongRunningWorkspaceCheckpoint(
        Instant capturedAt,
        Path projectDirectory,
        boolean gitRepository,
        Path gitRoot,
        String branch,
        String head,
        boolean dirty,
        String statusShort) {

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);

    public LongRunningWorkspaceCheckpoint {
        capturedAt = capturedAt == null ? Instant.now() : capturedAt;
        projectDirectory = Objects.requireNonNull(projectDirectory, "projectDirectory")
                .toAbsolutePath()
                .normalize();
        gitRoot = gitRoot == null ? null : gitRoot.toAbsolutePath().normalize();
        branch = normalize(branch);
        head = normalize(head);
        statusShort = statusShort == null ? "" : statusShort.stripTrailing();
    }

    public static LongRunningWorkspaceCheckpoint capture(Path projectDirectory) {
        Path cwd = Objects.requireNonNull(projectDirectory, "projectDirectory")
                .toAbsolutePath()
                .normalize();
        CommandResult root = run(cwd, "git", "rev-parse", "--show-toplevel");
        if (!root.success()) {
            return new LongRunningWorkspaceCheckpoint(
                    Instant.now(), cwd, false, null, null, null, false, "");
        }
        Path gitRoot = Path.of(root.output().strip()).toAbsolutePath().normalize();
        String branch = run(cwd, "git", "rev-parse", "--abbrev-ref", "HEAD").output().strip();
        String head = run(cwd, "git", "rev-parse", "HEAD").output().strip();
        String status = run(cwd, "git", "status", "--short").output().stripTrailing();
        return new LongRunningWorkspaceCheckpoint(
                Instant.now(),
                cwd,
                true,
                gitRoot,
                branch.isBlank() ? null : branch,
                head.isBlank() ? null : head,
                !status.isBlank(),
                status);
    }

    private static CommandResult run(Path cwd, String... command) {
        try {
            Process process = new ProcessBuilder(List.of(command))
                    .directory(cwd.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(false, "");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new CommandResult(process.exitValue() == 0, output);
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new CommandResult(false, "");
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.strip();
        return normalized.isBlank() ? null : normalized;
    }

    private record CommandResult(boolean success, String output) {}
}
