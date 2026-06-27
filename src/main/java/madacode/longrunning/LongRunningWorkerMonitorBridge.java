package madacode.longrunning;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.session.ConversationSession;
import madacode.core.session.SessionListener;
import madacode.tool.ToolNames;
import madacode.tui.TerminalText;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Bridges transient worker-session activity into the long-running task event log.
 *
 * <p>The bridge never touches terminal UI. It writes compact, bounded summaries
 * that the control-session monitor can render without tailing noisy tool output.
 */
public final class LongRunningWorkerMonitorBridge implements SessionListener {

    private static final int MAX_SUMMARY_COLUMNS = 120;
    private static final int MAX_OUTPUT_COLUMNS = 120;
    private static final long MIN_OUTPUT_INTERVAL_NANOS = 400_000_000L;

    private final LongRunningTaskStore store;
    private final String taskId;
    private final ConversationSession workerSession;
    private final ConcurrentMap<String, ToolState> tools = new ConcurrentHashMap<>();

    public LongRunningWorkerMonitorBridge(
            LongRunningTaskStore store,
            String taskId,
            ConversationSession workerSession) {
        this.store = Objects.requireNonNull(store, "store");
        this.taskId = requireNonBlank(taskId, "taskId");
        this.workerSession = Objects.requireNonNull(workerSession, "workerSession");
    }

    @Override
    public void onToolExecutionStarted(String toolUseId, String toolName, ObjectNode input) {
        String normalized = normalizeTool(toolName);
        String category = category(normalized);
        String summary = summarizeStarted(normalized, input);
        tools.put(toolUseId, new ToolState(normalized, category, summary, 0L, false));
        append("worker_tool_started", category, true, summary, Map.of(
                "workerSessionId", workerSession.sessionId(),
                "toolUseId", safe(toolUseId),
                "toolName", normalized,
                "category", category,
                "summary", summary));
    }

    @Override
    public void onToolExecutionProgress(String toolUseId, String progressText) {
        ToolState state = tools.get(toolUseId);
        if (state == null || !"bash".equals(state.category())) {
            return;
        }
        String summary = summarizeImportantOutput(progressText);
        if (summary.isBlank()) {
            return;
        }
        long now = System.nanoTime();
        if (now - state.lastOutputNanos() < MIN_OUTPUT_INTERVAL_NANOS) {
            return;
        }
        tools.put(toolUseId, state.withLastOutputNanos(now));
        append("worker_tool_output", state.category(), null, summary, Map.of(
                "workerSessionId", workerSession.sessionId(),
                "toolUseId", safe(toolUseId),
                "toolName", state.toolName(),
                "category", state.category(),
                "summary", summary,
                "source", "progress"));
    }

    @Override
    public void onToolExecutionActivity(String toolUseId, String activityText) {
        ToolState state = tools.get(toolUseId);
        if (state == null) {
            return;
        }
        if (!"bash".equals(state.category())) {
            return;
        }
        String summary = summarizeImportantOutput(activityText);
        if (summary.isBlank()) {
            return;
        }
        long now = System.nanoTime();
        if (now - state.lastOutputNanos() < MIN_OUTPUT_INTERVAL_NANOS) {
            return;
        }
        tools.put(toolUseId, state.withLastOutputNanos(now));
        append("worker_tool_output", state.category(), null, summary, Map.of(
                "workerSessionId", workerSession.sessionId(),
                "toolUseId", safe(toolUseId),
                "toolName", state.toolName(),
                "category", state.category(),
                "summary", summary,
                "source", "activity"));
    }

    @Override
    public void onToolResultAvailable(String toolUseId, boolean success, String output) {
        if (success) {
            return;
        }
        ToolState state = tools.get(toolUseId);
        if (state == null) {
            return;
        }
        String summary = summarizeFailure(state, output);
        tools.put(toolUseId, state.withResultRecorded(true));
        append("worker_tool_result", state.category(), false, summary, Map.of(
                "workerSessionId", workerSession.sessionId(),
                "toolUseId", safe(toolUseId),
                "toolName", state.toolName(),
                "category", state.category(),
                "summary", summary));
    }

    @Override
    public void onToolExecutionCompleted(String toolUseId, boolean success, long durationMs) {
        ToolState state = tools.remove(toolUseId);
        if (state == null) {
            return;
        }
        if (!success && !state.resultRecorded()) {
            append("worker_tool_completed", state.category(), false,
                    "Failed " + state.summary(), Map.of(
                            "workerSessionId", workerSession.sessionId(),
                            "toolUseId", safe(toolUseId),
                            "toolName", state.toolName(),
                            "category", state.category(),
                            "durationMs", String.valueOf(durationMs),
                            "summary", state.summary()));
        }
    }

    private void append(
            String type,
            String action,
            Boolean success,
            String message,
            Map<String, String> details) {
        try {
            store.appendEvent(taskId, LongRunningTaskEvent.of(
                    type,
                    taskId,
                    workerSession.sessionId(),
                    workerSession.longRunningStage() == null
                            ? null : workerSession.longRunningStage().name(),
                    action,
                    success,
                    message,
                    details));
        } catch (RuntimeException ignored) {
            // Monitor events are observability only; worker execution must continue.
        }
    }

    private static String summarizeStarted(String toolName, ObjectNode input) {
        return switch (toolName) {
            case ToolNames.FILE_READ, ToolNames.GREP, ToolNames.GLOB -> "Inspect project files";
            case ToolNames.FILE_EDIT -> "Edit " + fileName(field(input, "file_path"));
            case ToolNames.FILE_WRITE -> "Write " + fileName(field(input, "file_path"));
            case "bash" -> "Run " + firstNonBlank(field(input, "description"), field(input, "command"));
            case "longrun_environment_read" -> "Read long-running environment";
            case "longrun_environment_update" -> "Update long-running environment";
            default -> "Use " + firstNonBlank(toolName, "tool");
        };
    }

    private static String summarizeImportantOutput(String outputText) {
        String cleaned = clean(outputText);
        if (cleaned.isBlank()) {
            return "";
        }
        return looksLikeImportantOutput(cleaned) ? fit(cleaned, MAX_OUTPUT_COLUMNS) : "";
    }

    private static String summarizeFailure(ToolState state, String output) {
        String line = firstMeaningfulLine(output);
        if (line.isBlank()) {
            return state.summary() + " failed";
        }
        return state.summary() + " failed: " + fit(line, 80);
    }

    private static String firstMeaningfulLine(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String fallback = "";
        for (String line : output.lines().toList().reversed()) {
            String cleaned = clean(line);
            if (cleaned.isBlank()) {
                continue;
            }
            if (looksLikeFailure(cleaned)) {
                return cleaned;
            }
            if (fallback.isBlank()) {
                fallback = cleaned;
            }
        }
        return fallback;
    }

    private static boolean looksLikeFailure(String value) {
        String lower = value.toLowerCase();
        return lower.contains("failed")
                || lower.contains("failure")
                || lower.contains("error")
                || lower.contains("exception")
                || lower.contains("timed out")
                || lower.contains("cannot ")
                || lower.contains("not found");
    }

    private static boolean looksLikeImportantOutput(String value) {
        String lower = value.toLowerCase();
        return looksLikeFailure(value)
                || lower.contains("tests run:")
                || lower.contains("build success")
                || lower.contains("build failure")
                || lower.contains("compilation failure")
                || lower.contains("assertion")
                || lower.contains("failures:");
    }

    private static String category(String toolName) {
        return switch (toolName) {
            case ToolNames.FILE_READ, ToolNames.GREP, ToolNames.GLOB -> "inspect";
            case ToolNames.FILE_EDIT, ToolNames.FILE_WRITE -> "edit";
            case "bash" -> "bash";
            case "longrun_environment_read" -> "inspect";
            case "longrun_environment_update" -> "task_update";
            default -> "tool";
        };
    }

    private static String normalizeTool(String toolName) {
        return toolName == null ? "" : toolName.strip().toLowerCase();
    }

    private static String field(ObjectNode input, String field) {
        return input == null ? "" : clean(input.path(field).asText(""));
    }

    private static String fileName(String value) {
        if (value == null || value.isBlank()) {
            return "file";
        }
        Path path = Path.of(value);
        Path name = path.getFileName();
        return fit(name == null ? value : name.toString(), 72);
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ')
                .replace('\r', ' ')
                .replace('\t', ' ')
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String fit(String value, int columns) {
        return TerminalText.fitEnd(clean(value), columns);
    }

    private static String firstNonBlank(String preferred, String fallback) {
        String normalized = clean(preferred);
        return normalized.isBlank() ? fit(fallback, MAX_SUMMARY_COLUMNS) : fit(normalized, MAX_SUMMARY_COLUMNS);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        String normalized = value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private record ToolState(
            String toolName,
            String category,
            String summary,
            long lastOutputNanos,
            boolean resultRecorded) {
        ToolState withLastOutputNanos(long value) {
            return new ToolState(toolName, category, summary, value, resultRecorded);
        }

        ToolState withResultRecorded(boolean value) {
            return new ToolState(toolName, category, summary, lastOutputNanos, value);
        }
    }
}
