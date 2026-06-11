package madacode.logging;

import madacode.core.session.ConversationSession;
import madacode.core.model.FinishReason;
import madacode.events.EventContext;
import madacode.permission.PermissionDecision;

import java.nio.file.Path;
import java.util.Collection;

public final class DiagnosticEventLogger {

    private static final DiagnosticEvents DEFAULT = new DefaultDiagnosticEvents();

    private DiagnosticEventLogger() {
    }

    public static void turnStarted(ConversationSession session, Integer maxIterations) {
        DEFAULT.turnStarted(session, maxIterations);
    }

    public static void modelIterationCompleted(
            ConversationSession session,
            int iteration,
            long durationMs,
            int toolUseCount) {
        DEFAULT.modelIterationCompleted(session, iteration, durationMs, toolUseCount);
    }

    public static void turnCompleted(
            ConversationSession session,
            FinishReason finishReason,
            int iterations,
            long durationMs) {
        DEFAULT.turnCompleted(session, finishReason, iterations, durationMs);
    }

    public static void toolValidationFailed(
            ConversationSession session,
            String toolName,
            Collection<String> errors) {
        DEFAULT.toolValidationFailed(session, toolName, errors);
    }

    public static void permissionDecision(
            ConversationSession session,
            String toolName,
            PermissionDecision decision,
            long waitMs) {
        DEFAULT.permissionDecision(session, toolName, decision, waitMs);
    }

    public static void toolExecutionCompleted(
            ConversationSession session,
            String toolName,
            boolean success,
            long durationMs) {
        DEFAULT.toolExecutionCompleted(session, toolName, success, durationMs);
    }

    public static void transcriptSaved(ConversationSession session, Path path) {
        DEFAULT.transcriptSaved(session, path);
    }

    public static void transcriptLoaded(ConversationSession session, Path path) {
        DEFAULT.transcriptLoaded(session, path);
    }

    public static void apiRequest(String model, int messageCount, int maxTokens) {
        DEFAULT.apiRequest(model, messageCount, maxTokens);
    }

    public static void apiResponse(int statusCode, long durationMs) {
        DEFAULT.apiResponse(statusCode, durationMs);
    }

    public static void apiModelResponseFull(
            String model,
            int statusCode,
            int lineCount,
            int charCount,
            Path path) {
        DEFAULT.apiModelResponseFull(model, statusCode, lineCount, charCount, path);
    }

    public static void apiError(int statusCode, String bodyPreview) {
        DEFAULT.apiError(statusCode, bodyPreview);
    }

    public static void apiToolInputStream(
            String toolName,
            String toolUseId,
            int deltaCount,
            int inputChars,
            boolean empty) {
        DEFAULT.apiToolInputStream(toolName, toolUseId, deltaCount, inputChars, empty);
    }

    public static void apiToolInputJsonParseFailed(
            String toolName,
            String toolUseId,
            int inputChars) {
        DEFAULT.apiToolInputJsonParseFailed(toolName, toolUseId, inputChars);
    }

    public static boolean isModelResponseFullLoggingEnabled() {
        return isTruthy(firstNonBlank(
                System.getenv("MADA_MODEL_RESPONSE_LOG"),
                System.getProperty("MADA_MODEL_RESPONSE_LOG")));
    }

    public static void apiRetry(int attempt, int maxRetries, String type, long backoffMs) {
        DEFAULT.apiRetry(attempt, maxRetries, type, backoffMs);
    }

    public static void apiFinalFailure(int attempts, String type, boolean retryable) {
        DEFAULT.apiFinalFailure(attempts, type, retryable);
    }

    /** Turn-level supervisor caught a RuntimeException. Loud enough to be triaged. */
    public static void turnCrashed(ConversationSession session, Throwable error) {
        String sessionId = session == null ? "<none>" : session.sessionId();
        EventContext context = session == null
                ? EventContext.bootstrap("Turn")
                : EventContext.of(session, "Turn");
        DefaultDiagnosticEvents.error(context, "turn_crashed sessionId=%s error=\"%s\""
                .formatted(sessionId, DefaultDiagnosticEvents.sanitize(error == null ? "null" : error.toString())),
                error);
    }

    /** A SessionListener callback threw RT; we swallowed it to protect other subscribers. */
    public static void listenerCrashed(String callbackName, Throwable error) {
        DefaultDiagnosticEvents.warn(EventContext.bootstrap("SessionListener"),
                "listener_crashed callback=%s error=\"%s\""
                        .formatted(
                                callbackName,
                                DefaultDiagnosticEvents.sanitize(error == null ? "null" : error.toString())),
                error);
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

}
