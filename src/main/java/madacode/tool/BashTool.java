package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.session.ConversationSession;
import madacode.core.session.Subscription;
import madacode.core.engine.ToolExecutor;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class BashTool implements Tool<BashTool.Input> {

    public record Input(String command, String description, Integer timeoutSeconds, Integer timeout) {
        public Input(String command, String description, Integer timeoutSeconds) {
            this(command, description, timeoutSeconds, null);
        }
    }

    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000;
    private static final long MAX_TIMEOUT_MILLIS = 600_000;
    private static final int MAX_OUTPUT_CHARS = 30_000;
    private static final int MAX_CAPTURE_CHARS = MAX_OUTPUT_CHARS - 200;
    private static final int MAX_PROGRESS_LINES = 100;
    private static final int MAX_PROGRESS_LINE_CHARS = 1_000;

    @Override
    public String name() {
        return "bash";
    }

    @Override
    public String description() {
        return "Runs shell commands after permission approval. "
                + "Use for tests, builds, package scripts, git inspection, and system commands. "
                + "Prefer project-provided wrappers such as ./mvnw instead of system commands when present. "
                + "Do not use destructive commands such as rm, reset --hard, clean, force-push, or branch deletion "
                + "unless the user explicitly requested them or approves after you explain the risk. "
                + "If a command fails, inspect the error before retrying or changing approach. "
                + "Avoid interactive commands unless the user will run them directly.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public String approvalSignature(ObjectNode input) {
        // Approval semantics depend only on the actual command — description
        // and timeoutSeconds don't change what runs.
        return "cmd:" + input.path("command").asText("");
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("command", ToolSchemas.stringProperty(
                mapper, "The shell command to run."));
        properties.set("description", ToolSchemas.stringProperty(
                mapper, "Short explanation of why this command is needed."));
        properties.set("timeoutSeconds", ToolSchemas.integerProperty(
                mapper, "Optional command timeout in seconds.", 1, 600));
        properties.set("timeout", ToolSchemas.integerProperty(
                mapper, "Optional command timeout in milliseconds.", 1, (int) MAX_TIMEOUT_MILLIS));
        return ToolSchemas.objectSchema(mapper, properties, "command");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        String command = input.command();
        if (command == null || command.isBlank()) {
            return new ToolResult(name(), false, "Missing required field: command");
        }
        long timeoutMillis = resolveTimeoutMillis(input);
        if (context.cancellationToken().isCancelled()) {
            return new ToolResult(name(), false,
                    "Cancelled: " + context.cancellationToken().reason());
        }
        String toolUseId = ToolExecutor.CURRENT_TOOL_USE_ID.get();
        ConversationSession session = context.session();

        // OutputCollector created up front so EVERY exit path — including the
        // outer catch — can return whatever data the reader already buffered.
        // Decouples "data ownership" from "reader-thread completion": already-
        // collected lines can no longer be lost just because the reader is slow
        // to wind down.
        OutputCollector collector = new OutputCollector(session, toolUseId);
        Process process = null;
        Future<?> readerFuture = null;
        Subscription killSub = null;
        try {
            ProcessBuilder pb = processBuilder(command);
            pb.directory(context.workingDirectory().toFile());
            pb.redirectErrorStream(true);
            process = pb.start();
            final Process spawned = process;
            // Withdraw the kill-hook in finally so the cancellation token
            // doesn't accumulate a per-bash-call callback over the turn.
            killSub = context.cancellationToken().onCancel(() -> destroyProcessTree(spawned));

            readerFuture = CompletableFuture.runAsync(() -> readInto(spawned, collector));

            boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!finished) {
                destroyProcessTree(process);
                awaitReaderQuietly(readerFuture);
                String output = collector.snapshot();
                if (context.cancellationToken().isCancelled()) {
                    return new ToolResult(name(), false,
                            truncate(output + "\nCancelled: " + context.cancellationToken().reason()));
                }
                return new ToolResult(name(), false,
                        truncate(output + "\nCommand timed out after " + formatTimeout(timeoutMillis)));
            }
            int exitCode = process.exitValue();
            awaitReaderQuietly(readerFuture);
            String output = collector.snapshot();
            if (context.cancellationToken().isCancelled()) {
                return new ToolResult(name(), false,
                        truncate(output + "\nCancelled: " + context.cancellationToken().reason()));
            }
            return new ToolResult(name(), exitCode == 0,
                    truncate(exitCode == 0 ? output : appendExitCode(output, exitCode)));
        } catch (Exception e) {
            if (process != null && process.isAlive()) destroyProcessTree(process);
            if (readerFuture != null) awaitReaderQuietly(readerFuture);
            String partial = collector.snapshot();
            if (context.cancellationToken().isCancelled()) {
                return new ToolResult(name(), false,
                        truncate(prefixIfNonEmpty(partial)
                                + "Cancelled: " + context.cancellationToken().reason()));
            }
            return new ToolResult(name(), false,
                    truncate(prefixIfNonEmpty(partial)
                            + "Failed to run command: " + e.getMessage()));
        } finally {
            if (process != null && process.isAlive()) {
                destroyProcessTree(process);
            }
            if (killSub != null) killSub.close();
        }
    }

    private static void readInto(Process process, OutputCollector collector) {
        try (InputStreamReader reader =
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                collector.append(buffer, read);
            }
            collector.flushProgressLine();
        } catch (IOException e) {
            collector.appendLine("[reader error: " + e.getMessage() + "]");
        }
    }

    private static ProcessBuilder processBuilder(String command) {
        Path perl = Path.of("/usr/bin/perl");
        if (Files.isExecutable(perl)) {
            return new ProcessBuilder(
                    perl.toString(),
                    "-e",
                    "setpgrp(0,0); exec @ARGV",
                    "bash",
                    "-c",
                    command);
        }
        return new ProcessBuilder("bash", "-c", command);
    }

    private static long resolveTimeoutMillis(Input input) {
        if (input.timeout() != null && input.timeout() > 0) {
            return Math.min(input.timeout(), MAX_TIMEOUT_MILLIS);
        }
        if (input.timeoutSeconds() != null && input.timeoutSeconds() > 0) {
            long millis = TimeUnit.SECONDS.toMillis(input.timeoutSeconds());
            return Math.min(millis, MAX_TIMEOUT_MILLIS);
        }
        return DEFAULT_TIMEOUT_MILLIS;
    }

    private static String formatTimeout(long timeoutMillis) {
        if (timeoutMillis % 1000 == 0) {
            return (timeoutMillis / 1000) + "s";
        }
        return timeoutMillis + "ms";
    }

    private static void destroyProcessTree(Process process) {
        ProcessHandle handle = process.toHandle();
        long pid = handle.pid();
        killProcessGroup(pid, "TERM");
        sleepQuietly(100);
        try {
            List<ProcessHandle> descendants = new ArrayList<>(handle.descendants().toList());
            Collections.reverse(descendants);
            for (ProcessHandle descendant : descendants) {
                try {
                    descendant.destroyForcibly();
                } catch (RuntimeException ignored) {
                    // Process may already have exited, or the OS denied access.
                }
            }
        } catch (RuntimeException ignored) {
            // Some sandboxed platforms deny process-tree enumeration. Fall back
            // to killing the shell process so timeout/cancel still completes.
            destroyProcessTreeWithPgrep(pid, new HashSet<>());
        }
        killProcessGroup(pid, "KILL");
        try {
            handle.destroyForcibly();
        } catch (RuntimeException ignored) {
            // Process may already have exited.
        }
    }

    private static void killProcessGroup(long pid, String signal) {
        killTarget("-" + pid, signal);
    }

    private static void destroyProcessTreeWithPgrep(long pid, Set<Long> seen) {
        if (!seen.add(pid)) {
            return;
        }
        for (long child : childPids(pid)) {
            destroyProcessTreeWithPgrep(child, seen);
        }
        killPid(pid);
    }

    private static List<Long> childPids(long pid) {
        Process process = null;
        try {
            process = new ProcessBuilder("pgrep", "-P", Long.toString(pid)).start();
            boolean finished = process.waitFor(500, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return List.of();
            }
            if (process.exitValue() != 0) {
                return List.of();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            List<Long> pids = new ArrayList<>();
            for (String line : output.split("\\R")) {
                if (!line.isBlank()) {
                    pids.add(Long.parseLong(line.trim()));
                }
            }
            return pids;
        } catch (IOException | InterruptedException | NumberFormatException ignored) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }
    }

    private static void killPid(long pid) {
        killTarget(Long.toString(pid), "KILL");
    }

    private static void killTarget(String target, String signal) {
        Process process = null;
        try {
            process = new ProcessBuilder("kill", "-" + signal, target).start();
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (IOException | InterruptedException ignored) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (ignored instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Give the reader a brief chance to wind down on its own; if it doesn't,
     *  we don't care — the collector already holds whatever it managed to read. */
    private static void awaitReaderQuietly(Future<?> future) {
        try {
            future.get(1, TimeUnit.SECONDS);
        } catch (TimeoutException | ExecutionException ignored) {
            // ignored — partial data is already in the collector
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String prefixIfNonEmpty(String s) {
        return s.isEmpty() ? "" : s + "\n";
    }

    private static String appendExitCode(String output, int exitCode) {
        return prefixIfNonEmpty(output == null ? "" : output.stripTrailing())
                + "Exit code: " + exitCode;
    }

    private String truncate(String output) {
        if (output.length() > MAX_OUTPUT_CHARS) {
            return output.substring(0, MAX_OUTPUT_CHARS) + "\n... (truncated)";
        }
        return output;
    }

    /**
     * Thread-safe collector for subprocess stdout/stderr.
     *
     * <p>Reader thread appends; any thread may {@link #snapshot()} at any time.
     * Decoupling collection from delivery means a slow or stuck reader thread
     * cannot cause loss of data that has already been read. This is the
     * structural fix for Bug 8.
     *
     * <p>The lock guards only the {@link StringBuilder}; firing the per-line
     * progress event happens outside the lock so a slow listener cannot block
     * {@link #snapshot()} on the main thread.
     */
    private static final class OutputCollector {
        private final Object bufLock = new Object();
        private final StringBuilder buf = new StringBuilder();
        private long droppedChars;
        private final ConversationSession session;
        private final String toolUseId;
        private final StringBuilder progressLine = new StringBuilder();
        private boolean progressLineTruncated;
        // Accessed only on the reader thread — no synchronization needed.
        private int progressCount;

        OutputCollector(ConversationSession session, String toolUseId) {
            this.session = session;
            this.toolUseId = toolUseId;
        }

        void appendLine(String line) {
            append(line.toCharArray(), line.length());
            append(new char[] {'\n'}, 1);
        }

        void append(char[] chunk, int length) {
            synchronized (bufLock) {
                int remaining = MAX_CAPTURE_CHARS - buf.length();
                if (remaining > 0) {
                    int toAppend = Math.min(remaining, length);
                    buf.append(chunk, 0, toAppend);
                    droppedChars += length - toAppend;
                } else {
                    droppedChars += length;
                }
            }
            appendProgress(chunk, length);
        }

        private void appendProgress(char[] chunk, int length) {
            if (toolUseId == null || progressCount >= MAX_PROGRESS_LINES) {
                return;
            }
            for (int i = 0; i < length; i++) {
                char c = chunk[i];
                if (c == '\n') {
                    fireProgressLine();
                } else if (c != '\r') {
                    if (progressLine.length() < MAX_PROGRESS_LINE_CHARS) {
                        progressLine.append(c);
                    } else {
                        progressLineTruncated = true;
                    }
                }
            }
        }

        void flushProgressLine() {
            if (!progressLine.isEmpty()) {
                fireProgressLine();
            }
        }

        private void fireProgressLine() {
            if (toolUseId != null && progressCount < MAX_PROGRESS_LINES) {
                String line = progressLine.toString();
                if (progressLineTruncated) {
                    line += "... (line truncated)";
                }
                session.fireToolExecutionProgress(toolUseId, line);
                progressCount++;
            }
            progressLine.setLength(0);
            progressLineTruncated = false;
        }

        String snapshot() {
            synchronized (bufLock) {
                if (droppedChars == 0) {
                    return buf.toString();
                }
                return buf + "\n... [" + droppedChars + " chars truncated] ...\n";
            }
        }
    }
}
