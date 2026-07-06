package madacode.eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs a child process with bounded time and output, consuming stdout concurrently so a
 * full pipe can never defeat timeout enforcement.
 */
public final class ProcessSupervisor {

    public Outcome run(List<String> command, Path workingDirectory, Duration timeout, int maxOutputBytes) {
        return run(command, workingDirectory, timeout, maxOutputBytes, Map.of());
    }

    public Outcome run(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            int maxOutputBytes,
            Map<String, String> environment) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(timeout, "timeout");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }

        Process process = null;
        ExecutorService reader = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("mada-eval-process-output-", 0).factory());
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true);
            if (environment != null && !environment.isEmpty()) {
                builder.environment().putAll(environment);
            }
            process = builder.start();
            BoundedOutput output = new BoundedOutput(maxOutputBytes);
            Process spawned = process;
            Future<?> drain = reader.submit(() -> drain(spawned.getInputStream(), output));

            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                destroyProcessTree(process);
            }
            awaitDrain(drain);

            if (!finished) {
                return new Outcome(Status.TIMED_OUT, -1, output.text(), output.truncated());
            }
            return new Outcome(Status.EXITED, process.exitValue(), output.text(), output.truncated());
        } catch (IOException e) {
            return new Outcome(Status.START_FAILED, -1, e.getMessage(), false);
        } catch (InterruptedException e) {
            if (process != null) {
                destroyProcessTree(process);
            }
            Thread.currentThread().interrupt();
            return new Outcome(Status.INTERRUPTED, -1, "process interrupted", false);
        } finally {
            reader.shutdownNow();
        }
    }

    public ManagedProcess start(
            List<String> command,
            Path workingDirectory,
            int maxOutputBytes,
            Map<String, String> environment) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        if (maxOutputBytes <= 0) {
            throw new IllegalArgumentException("maxOutputBytes must be positive");
        }
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true);
            if (environment != null && !environment.isEmpty()) {
                builder.environment().putAll(environment);
            }
            Process process = builder.start();
            BoundedOutput output = new BoundedOutput(maxOutputBytes);
            ExecutorService reader = Executors.newSingleThreadExecutor(
                    Thread.ofVirtual().name("mada-eval-process-output-", 0).factory());
            Future<?> drain = reader.submit(() -> drain(process.getInputStream(), output));
            return new ManagedProcess(process, output, reader, drain);
        } catch (IOException e) {
            throw new IllegalStateException("failed to start process: " + e.getMessage(), e);
        }
    }

    private static void drain(InputStream input, BoundedOutput output) {
        byte[] buffer = new byte[8192];
        try (input) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, read);
            }
        } catch (IOException ignored) {
            // Process termination commonly closes the stream while the reader is blocked.
        }
    }

    private static void awaitDrain(Future<?> drain) throws InterruptedException {
        try {
            drain.get(5, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            drain.cancel(true);
        } catch (ExecutionException ignored) {
            // The bounded output already contains everything read before the failure.
        }
    }

    private static void destroyProcessTree(Process process) {
        ProcessHandle handle = process.toHandle();
        handle.descendants().forEach(ProcessHandle::destroy);
        handle.destroy();
        try {
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                handle.descendants().forEach(ProcessHandle::destroyForcibly);
                handle.destroyForcibly();
                process.waitFor(500, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            handle.descendants().forEach(ProcessHandle::destroyForcibly);
            handle.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    public enum Status {
        EXITED,
        TIMED_OUT,
        START_FAILED,
        INTERRUPTED
    }

    public record Outcome(Status status, int exitCode, String output, boolean outputTruncated) {
        public boolean succeeded() {
            return status == Status.EXITED && exitCode == 0;
        }
    }

    public static final class ManagedProcess implements AutoCloseable {
        private final Process process;
        private final BoundedOutput output;
        private final ExecutorService reader;
        private final Future<?> drain;

        private ManagedProcess(
                Process process,
                BoundedOutput output,
                ExecutorService reader,
                Future<?> drain) {
            this.process = process;
            this.output = output;
            this.reader = reader;
            this.drain = drain;
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        public Outcome await(Duration timeout) {
            try {
                boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (!finished) {
                    return new Outcome(Status.TIMED_OUT, -1, output.text(), output.truncated());
                }
                awaitDrain(drain);
                return new Outcome(Status.EXITED, process.exitValue(), output.text(), output.truncated());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Outcome(Status.INTERRUPTED, -1, "process interrupted", false);
            }
        }

        public String output() {
            return output.text();
        }

        @Override
        public void close() {
            if (process.isAlive()) {
                destroyProcessTree(process);
            }
            try {
                awaitDrain(drain);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                reader.shutdownNow();
            }
        }
    }

    private static final class BoundedOutput {
        private final int limit;
        private final ByteArrayOutputStream bytes;
        private boolean truncated;

        private BoundedOutput(int limit) {
            this.limit = limit;
            this.bytes = new ByteArrayOutputStream(Math.min(limit, 8192));
        }

        private synchronized void write(byte[] source, int length) {
            int remaining = limit - bytes.size();
            if (remaining > 0) {
                bytes.write(source, 0, Math.min(remaining, length));
            }
            if (length > remaining) {
                truncated = true;
            }
        }

        private synchronized String text() {
            String text = bytes.toString(StandardCharsets.UTF_8);
            return truncated ? text + "\n…(process output truncated)" : text;
        }

        private synchronized boolean truncated() {
            return truncated;
        }
    }
}
