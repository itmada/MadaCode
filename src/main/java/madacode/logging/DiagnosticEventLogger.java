package madacode.logging;

import madacode.core.session.ConversationSession;
import madacode.core.model.FinishReason;
import madacode.events.AppEvents;
import madacode.events.DiagnosticEvent;
import madacode.events.EventContext;
import madacode.permission.BashSafetyPermissionRule;
import madacode.permission.PermissionDecision;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;

public final class DiagnosticEventLogger {

    private DiagnosticEventLogger() {
    }

    public static void turnStarted(ConversationSession session, Integer maxIterations) {
        String limit = maxIterations == null ? "unbounded" : maxIterations.toString();
        emit(session, "turn_started sessionId=%s maxIterations=%s messages=%d cwd=%s"
                .formatted(session.sessionId(), limit, session.messages().size(), session.workingDirectory()));
    }

    public static void modelIterationCompleted(
            ConversationSession session,
            int iteration,
            long durationMs,
            int toolUseCount) {
        emit(session, "model_iteration_completed sessionId=%s iteration=%d durationMs=%d toolUses=%d"
                .formatted(session.sessionId(), iteration, durationMs, toolUseCount));
    }

    public static void turnCompleted(
            ConversationSession session,
            FinishReason finishReason,
            int iterations,
            long durationMs) {
        emit(session, "turn_completed sessionId=%s finishReason=%s iterations=%d durationMs=%d messages=%d"
                .formatted(session.sessionId(), finishReason, iterations, durationMs, session.messages().size()));
    }

    public static void toolValidationFailed(
            ConversationSession session,
            String toolName,
            Collection<String> errors) {
        emit(session, "tool_validation_failed sessionId=%s tool=%s errors=\"%s\""
                .formatted(session.sessionId(), toolName, sanitize(String.join("; ", errors))));
    }

    public static void permissionDecision(
            ConversationSession session,
            String toolName,
            PermissionDecision decision,
            long waitMs) {
        String message = "permission_decision sessionId=%s tool=%s source=%s allowed=%s waitMs=%d reason=\"%s\""
                .formatted(
                        session.sessionId(),
                        toolName,
                        decision.source(),
                        decision.isAllowed(),
                        waitMs,
                        sanitize(decision.reason()));
        if (!decision.isAllowed() && BashSafetyPermissionRule.SOURCE.equals(decision.source())) {
            warn(EventContext.of(session, "Permission"), message);
        } else {
            debug(EventContext.of(session, "Permission"), message);
        }
    }

    public static void toolExecutionCompleted(
            ConversationSession session,
            String toolName,
            boolean success,
            long durationMs) {
        emit(session, "tool_execution_completed sessionId=%s tool=%s success=%s durationMs=%d"
                .formatted(session.sessionId(), toolName, success, durationMs));
    }

    public static void transcriptSaved(ConversationSession session, Path path) {
        emit(session, "transcript_saved sessionId=%s path=%s messages=%d"
                .formatted(session.sessionId(), path, session.messages().size()));
    }

    public static void transcriptLoaded(ConversationSession session, Path path) {
        emit(session, "transcript_loaded sessionId=%s path=%s messages=%d"
                .formatted(session.sessionId(), path, session.messages().size()));
    }

    public static void apiRequest(String model, int messageCount, int maxTokens) {
        debug(EventContext.bootstrap("ApiClient"),
                "api_request model=%s messages=%d maxTokens=%d"
                        .formatted(model, messageCount, maxTokens));
    }

    public static void apiResponse(int statusCode, long durationMs) {
        debug(EventContext.bootstrap("ApiClient"),
                "api_response status=%d durationMs=%d".formatted(statusCode, durationMs));
    }

    public static void apiModelResponseFull(
            String model,
            int statusCode,
            int lineCount,
            int charCount,
            Path path) {
        if (path == null) {
            warn(EventContext.bootstrap("ApiClient"),
                    "api_model_response_full model=%s status=%d lines=%d chars=%d path=<write-failed>"
                            .formatted(sanitize(model), statusCode, lineCount, charCount));
            return;
        }
        debug(EventContext.bootstrap("ApiClient"),
                "api_model_response_full model=%s status=%d lines=%d chars=%d path=%s"
                        .formatted(sanitize(model), statusCode, lineCount, charCount, path));
    }

    public static void apiError(int statusCode, String bodyPreview) {
        warn(EventContext.bootstrap("ApiClient"), "api_error status=%d".formatted(statusCode));
        debug(EventContext.bootstrap("ApiClient"),
                "api_error_body preview=\"%s\"".formatted(sanitize(bodyPreview)));
    }

    public static void apiToolInputStream(
            String toolName,
            String toolUseId,
            int deltaCount,
            int inputChars,
            boolean empty) {
        debug(EventContext.bootstrap("ApiClient"),
                "api_tool_input_stream tool=%s id=%s deltas=%d chars=%d empty=%s"
                        .formatted(
                                sanitize(toolName),
                                sanitize(toolUseId),
                                deltaCount,
                                inputChars,
                                empty));
    }

    public static void apiToolInputJsonParseFailed(
            String toolName,
            String toolUseId,
            int inputChars) {
        warn(EventContext.bootstrap("ApiClient"),
                "api_tool_input_json_parse_failed tool=%s id=%s chars=%d"
                        .formatted(
                                sanitize(toolName),
                                sanitize(toolUseId),
                                inputChars));
    }

    public static boolean isModelResponseFullLoggingEnabled() {
        return isTruthy(firstNonBlank(
                System.getenv("MADA_MODEL_RESPONSE_LOG"),
                System.getProperty("MADA_MODEL_RESPONSE_LOG")));
    }

    public static void apiRetry(int attempt, int maxRetries, String type, long backoffMs) {
        debug(EventContext.bootstrap("ApiClient"),
                "api_retry attempt=%d maxRetries=%d type=%s backoffMs=%d"
                        .formatted(attempt, maxRetries, type, backoffMs));
    }

    public static void apiFinalFailure(int attempts, String type, boolean retryable) {
        debug(EventContext.bootstrap("ApiClient"),
                "api_final_failure attempts=%d type=%s retryable=%s"
                        .formatted(attempts, type, retryable));
    }

    /** Turn-level supervisor caught a RuntimeException. Loud enough to be triaged. */
    public static void turnCrashed(ConversationSession session, Throwable error) {
        String sessionId = session == null ? "<none>" : session.sessionId();
        EventContext context = session == null
                ? EventContext.bootstrap("Turn")
                : EventContext.of(session, "Turn");
        error(context, "turn_crashed sessionId=%s error=\"%s\""
                .formatted(sessionId, sanitize(error == null ? "null" : error.toString())), error);
    }

    /** A SessionListener callback threw RT; we swallowed it to protect other subscribers. */
    public static void listenerCrashed(String callbackName, Throwable error) {
        warn(EventContext.bootstrap("SessionListener"),
                "listener_crashed callback=%s error=\"%s\""
                        .formatted(callbackName, sanitize(error == null ? "null" : error.toString())),
                error);
    }

    private static void emit(ConversationSession session, String message) {
        Objects.requireNonNull(session, "session");
        debug(EventContext.of(session, "Session"), message);
    }

    private static void debug(EventContext context, String message) {
        AppEvents.publisher().publish(DiagnosticEvent.debug(context, message));
    }

    private static void warn(EventContext context, String message) {
        AppEvents.publisher().publish(DiagnosticEvent.warn(context, message));
    }

    private static void warn(EventContext context, String message, Throwable error) {
        AppEvents.publisher().publish(DiagnosticEvent.warn(context, message, error));
    }

    private static void error(EventContext context, String message, Throwable error) {
        AppEvents.publisher().publish(DiagnosticEvent.error(context, message, error));
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').replace('"', '\'');
    }
}
