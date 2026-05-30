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

    public static void turnStarted(ConversationSession session, int maxIterations) {
        emit(session, "turn_started sessionId=%s maxIterations=%d messages=%d cwd=%s"
                .formatted(session.sessionId(), maxIterations, session.messages().size(), session.workingDirectory()));
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

    public static void apiRequest(String model, int messageCount) {
        debug(EventContext.bootstrap("ApiClient"),
                "api_request model=%s messages=%d".formatted(model, messageCount));
    }

    public static void apiResponse(int statusCode, long durationMs) {
        debug(EventContext.bootstrap("ApiClient"),
                "api_response status=%d durationMs=%d".formatted(statusCode, durationMs));
    }

    public static void apiError(int statusCode, String bodyPreview) {
        warn(EventContext.bootstrap("ApiClient"), "api_error status=%d".formatted(statusCode));
        debug(EventContext.bootstrap("ApiClient"),
                "api_error_body preview=\"%s\"".formatted(sanitize(bodyPreview)));
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

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').replace('"', '\'');
    }
}
