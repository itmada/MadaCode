package madacode.eval;

import madacode.core.session.ConversationSession;
import madacode.permission.PermissionMode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Drives an eval case through the Claude Code CLI instead of MadaCode's own model loop.
 *
 * <p>Runs {@code claude -p} in the attempt workspace so Claude Code mutates files there;
 * scoring still happens through the existing verify pipeline. Trajectory / safety /
 * efficiency dimensions are intentionally unsupported — this launcher returns
 * {@link RunMetrics#ZERO} and does not reconstruct tool traces.
 */
public final class ClaudeCodeModeLauncher implements ModeLauncher {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String claudeBinary;
    private final ProcessSupervisor processSupervisor;
    private final String modeId;

    public ClaudeCodeModeLauncher() {
        this("claude", new ProcessSupervisor(), "claude-code");
    }

    public ClaudeCodeModeLauncher(String claudeBinary, ProcessSupervisor processSupervisor) {
        this(claudeBinary, processSupervisor, "claude-code");
    }

    public ClaudeCodeModeLauncher(
            String claudeBinary,
            ProcessSupervisor processSupervisor,
            String modeId) {
        this.claudeBinary = Objects.requireNonNull(claudeBinary, "claudeBinary");
        this.processSupervisor = Objects.requireNonNull(processSupervisor, "processSupervisor");
        this.modeId = Objects.requireNonNull(modeId, "modeId");
        if (claudeBinary.isBlank()) {
            throw new IllegalArgumentException("claudeBinary must not be blank");
        }
        if (modeId.isBlank()) {
            throw new IllegalArgumentException("modeId must not be blank");
        }
    }

    @Override
    public String modeId() {
        return modeId;
    }

    @Override
    public LaunchOutcome launch(EvalCase evalCase, ConversationSession session, EvalRunContext context) {
        Path workspace = session.workingDirectory();
        Path settingsFile = null;
        try {
            settingsFile = Files.createTempFile("mada-eval-claude-settings-", ".json");
            try {
                Files.setPosixFilePermissions(settingsFile, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // The default temporary-file permissions are restrictive on supported hosts.
            }
            Files.writeString(settingsFile, settingsJson(context), StandardCharsets.UTF_8);
            settingsFile.toFile().deleteOnExit();

            List<String> command = buildCommand(evalCase, settingsFile);
            System.out.println("Claude attempt started: " + evalCase.id());
            // Use the effective timeout so ProcessSupervisor kills the claude process tree
            // before LocalAttemptExecutor's outer grace deadline cancels the launcher thread.
            ProcessSupervisor.Outcome outcome = processSupervisor.run(
                    command,
                    workspace,
                    effectiveTimeout(context.budget().caseTimeout()),
                    context.budget().maxProcessOutputBytes());
            System.out.println("Claude attempt finished: " + evalCase.id()
                    + " status=" + outcome.status());
            return toLaunchOutcome(outcome);
        } catch (IOException e) {
            return toLaunchOutcome(new ProcessSupervisor.Outcome(
                    ProcessSupervisor.Status.START_FAILED,
                    -1,
                    "failed to prepare Claude settings: " + e.getMessage(),
                    false));
        } finally {
            if (settingsFile != null) {
                try {
                    Files.deleteIfExists(settingsFile);
                } catch (IOException ignored) {
                    // deleteOnExit remains as a final fallback for an interrupted run.
                }
            }
        }
    }

    private List<String> buildCommand(EvalCase evalCase, Path settingsFile) {
        List<String> command = new ArrayList<>();
        command.add(claudeBinary);
        command.add("-p");
        command.add("--output-format");
        command.add("json");
        command.add("--effort");
        command.add("max");
        command.add("--no-session-persistence");
        // Align with MadaCode's eval posture: no MCP servers and no skills.
        command.add("--strict-mcp-config");
        command.add("--disable-slash-commands");
        // Point Claude Code at the same provider MadaCode uses. Use a short-lived settings
        // file so the auth token does not appear in the process command line.
        command.add("--settings");
        command.add(settingsFile.toString());
        appendPermissionFlags(command, evalCase.permissionMode());
        command.add(evalCase.instruction());
        return command;
    }

    private static Duration effectiveTimeout(Duration configured) {
        String raw = System.getenv("MADA_EVAL_CLAUDE_TIMEOUT_SECONDS");
        if (raw == null || raw.isBlank()) {
            return configured;
        }
        try {
            long seconds = Long.parseLong(raw);
            if (seconds <= 0) {
                return configured;
            }
            Duration override = Duration.ofSeconds(seconds);
            return override.compareTo(configured) < 0 ? override : configured;
        } catch (NumberFormatException ignored) {
            return configured;
        }
    }

    private static String settingsJson(EvalRunContext context) {
        if (context.runtime() == null) {
            return "{}";
        }
        madacode.provider.Provider provider = context.runtime().provider();
        String model = provider.defaultModel();
        String baseUrl = provider.baseUrl().toString();
        String token = provider.authToken();
        return "{\"env\":{"
                + "\"ANTHROPIC_BASE_URL\":\"" + jsonEscape(baseUrl) + "\","
                + "\"ANTHROPIC_AUTH_TOKEN\":\"" + jsonEscape(token) + "\","
                + "\"ANTHROPIC_API_KEY\":\"\","
                + "\"ANTHROPIC_MODEL\":\"" + jsonEscape(model) + "\","
                + "\"ANTHROPIC_DEFAULT_OPUS_MODEL\":\"" + jsonEscape(model) + "\","
                + "\"ANTHROPIC_DEFAULT_HAIKU_MODEL\":\"" + jsonEscape(model) + "\","
                + "\"ANTHROPIC_DEFAULT_SONNET_MODEL\":\"" + jsonEscape(model) + "\""
                + "}}";
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void appendPermissionFlags(List<String> command, PermissionMode mode) {
        switch (mode) {
            case BYPASS -> command.add("--dangerously-skip-permissions");
            case EDIT -> {
                command.add("--permission-mode");
                command.add("acceptEdits");
            }
            case DEFAULT -> {
                command.add("--permission-mode");
                command.add("dontAsk");
            }
        }
    }

    private static LaunchOutcome toLaunchOutcome(ProcessSupervisor.Outcome outcome) {
        String output = outcome.output() == null ? "" : outcome.output();
        String finalText = extractFinalText(output);
        return switch (outcome.status()) {
            case EXITED -> new LaunchOutcome(
                    outcome.exitCode() == 0
                            ? EvalResult.ExecutionStatus.COMPLETED
                            : EvalResult.ExecutionStatus.WORKFLOW_FAILED,
                    RunMetrics.ZERO,
                    outcome.exitCode() == 0 ? "COMPLETED" : "WORKFLOW_FAILED exit=" + outcome.exitCode(),
                    output,
                    finalText,
                    true);
            case TIMED_OUT -> new LaunchOutcome(
                    EvalResult.ExecutionStatus.TIMED_OUT,
                    RunMetrics.ZERO,
                    "TIMED_OUT",
                    output,
                    finalText,
                    true);
            case INTERRUPTED -> new LaunchOutcome(
                    EvalResult.ExecutionStatus.CANCELLED,
                    RunMetrics.ZERO,
                    "CANCELLED",
                    output,
                    finalText,
                    true);
            case START_FAILED -> new LaunchOutcome(
                    EvalResult.ExecutionStatus.CRASHED,
                    RunMetrics.ZERO,
                    "CRASHED",
                    output.isBlank() ? "failed to start Claude Code CLI" : output,
                    finalText,
                    true);
        };
    }

    /** Best-effort parse of {@code --output-format json}; falls back to raw stdout. */
    private static String extractFinalText(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        try {
            JsonNode root = MAPPER.readTree(output);
            if (root != null && root.hasNonNull("result")) {
                return root.get("result").asText("");
            }
        } catch (Exception ignored) {
            // Claude Code may emit non-JSON diagnostics; keep raw output.
        }
        return output;
    }
}
