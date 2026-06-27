package madacode.logging;

import madacode.core.model.FinishReason;
import madacode.core.session.ConversationSession;
import madacode.events.AppEventPublisher;
import madacode.events.AppEvents;
import madacode.events.DiagnosticEvent;
import madacode.events.EventContext;
import madacode.permission.BashSafetyPermissionRule;
import madacode.permission.PermissionDecision;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;

public final class DefaultDiagnosticEvents implements DiagnosticEvents {

    private final AppEventPublisher publisher;

    public DefaultDiagnosticEvents() {
        this(AppEvents.publisher());
    }

    public DefaultDiagnosticEvents(AppEventPublisher publisher) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
    }

    @Override
    public void turnStarted(ConversationSession session, Integer maxIterations) {
        String limit = maxIterations == null ? "unbounded" : maxIterations.toString();
        emit(session, "turn_started sessionId=%s maxIterations=%s messages=%d cwd=%s"
                .formatted(session.sessionId(), limit, session.messages().size(), session.workingDirectory()));
    }

    @Override
    public void modelIterationCompleted(
            ConversationSession session,
            int iteration,
            long durationMs,
            int toolUseCount) {
        emit(session, "model_iteration_completed sessionId=%s iteration=%d durationMs=%d toolUses=%d"
                .formatted(session.sessionId(), iteration, durationMs, toolUseCount));
    }

    @Override
    public void turnCompleted(
            ConversationSession session,
            FinishReason finishReason,
            int iterations,
            long durationMs) {
        emit(session, "turn_completed sessionId=%s finishReason=%s iterations=%d durationMs=%d messages=%d"
                .formatted(session.sessionId(), finishReason, iterations, durationMs, session.messages().size()));
    }

    @Override
    public void toolValidationFailed(
            ConversationSession session,
            String toolName,
            Collection<String> errors) {
        emit(session, "tool_validation_failed sessionId=%s tool=%s errors=\"%s\""
                .formatted(session.sessionId(), toolName, sanitize(String.join("; ", errors))));
    }

    @Override
    public void permissionDecision(
            ConversationSession session,
            String toolName,
            PermissionDecision decision,
            long waitMs) {
        String message = "permission_decision sessionId=%s tool=%s layer=%s source=%s allowed=%s waitMs=%d reason=\"%s\""
                .formatted(
                        session.sessionId(),
                        toolName,
                        decision.layer(),
                        decision.source(),
                        decision.isAllowed(),
                        waitMs,
                        sanitize(decision.reason()));
        if (!decision.isAllowed() && BashSafetyPermissionRule.SOURCE.equals(decision.source())) {
            warnEvent(EventContext.of(session, "Permission"), message);
        } else {
            debugEvent(EventContext.of(session, "Permission"), message);
        }
    }

    @Override
    public void toolExecutionCompleted(
            ConversationSession session,
            String toolName,
            boolean success,
            long durationMs) {
        emit(session, "tool_execution_completed sessionId=%s tool=%s success=%s durationMs=%d"
                .formatted(session.sessionId(), toolName, success, durationMs));
    }

    @Override
    public void transcriptSaved(ConversationSession session, Path path) {
        emit(session, "transcript_saved sessionId=%s path=%s messages=%d"
                .formatted(session.sessionId(), path, session.messages().size()));
    }

    @Override
    public void transcriptLoaded(ConversationSession session, Path path) {
        emit(session, "transcript_loaded sessionId=%s path=%s messages=%d"
                .formatted(session.sessionId(), path, session.messages().size()));
    }

    @Override
    public void apiRequest(String model, int messageCount, int maxTokens) {
        debugEvent(EventContext.bootstrap("ApiClient"),
                "api_request model=%s messages=%d maxTokens=%d"
                        .formatted(model, messageCount, maxTokens));
    }

    @Override
    public void apiResponse(int statusCode, long durationMs) {
        debugEvent(EventContext.bootstrap("ApiClient"),
                "api_response status=%d durationMs=%d".formatted(statusCode, durationMs));
    }

    @Override
    public void apiModelResponseFull(
            String model,
            int statusCode,
            int lineCount,
            int charCount,
            Path path) {
        if (path == null) {
            warnEvent(EventContext.bootstrap("ApiClient"),
                    "api_model_response_full model=%s status=%d lines=%d chars=%d path=<write-failed>"
                            .formatted(sanitize(model), statusCode, lineCount, charCount));
            return;
        }
        debugEvent(EventContext.bootstrap("ApiClient"),
                "api_model_response_full model=%s status=%d lines=%d chars=%d path=%s"
                        .formatted(sanitize(model), statusCode, lineCount, charCount, path));
    }

    @Override
    public void apiError(int statusCode, String bodyPreview) {
        warnEvent(EventContext.bootstrap("ApiClient"), "api_error status=%d".formatted(statusCode));
        debugEvent(EventContext.bootstrap("ApiClient"),
                "api_error_body preview=\"%s\"".formatted(sanitize(bodyPreview)));
    }

    @Override
    public void apiToolInputStream(
            String toolName,
            String toolUseId,
            int deltaCount,
            int inputChars,
            boolean empty) {
        debugEvent(EventContext.bootstrap("ApiClient"),
                "api_tool_input_stream tool=%s id=%s deltas=%d chars=%d empty=%s"
                        .formatted(
                                sanitize(toolName),
                                sanitize(toolUseId),
                                deltaCount,
                                inputChars,
                                empty));
    }

    @Override
    public void apiToolInputJsonParseFailed(
            String toolName,
            String toolUseId,
            int inputChars) {
        warnEvent(EventContext.bootstrap("ApiClient"),
                "api_tool_input_json_parse_failed tool=%s id=%s chars=%d"
                        .formatted(
                                sanitize(toolName),
                                sanitize(toolUseId),
                                inputChars));
    }

    @Override
    public void apiRetry(int attempt, int maxRetries, String type, long backoffMs) {
        debugEvent(EventContext.bootstrap("ApiClient"),
                "api_retry attempt=%d maxRetries=%d type=%s backoffMs=%d"
                        .formatted(attempt, maxRetries, type, backoffMs));
    }

    @Override
    public void apiFinalFailure(int attempts, String type, boolean retryable) {
        debugEvent(EventContext.bootstrap("ApiClient"),
                "api_final_failure attempts=%d type=%s retryable=%s"
                        .formatted(attempts, type, retryable));
    }

    private void debugEvent(EventContext context, String message) {
        publisher.publish(DiagnosticEvent.debug(context, message));
    }

    private void warnEvent(EventContext context, String message) {
        publisher.publish(DiagnosticEvent.warn(context, message));
    }

    private void warnEvent(EventContext context, String message, Throwable error) {
        publisher.publish(DiagnosticEvent.warn(context, message, error));
    }

    private void errorEvent(EventContext context, String message, Throwable error) {
        publisher.publish(DiagnosticEvent.error(context, message, error));
    }

    static void debug(EventContext context, String message) {
        AppEvents.publisher().publish(DiagnosticEvent.debug(context, message));
    }

    static void warn(EventContext context, String message) {
        AppEvents.publisher().publish(DiagnosticEvent.warn(context, message));
    }

    static void warn(EventContext context, String message, Throwable error) {
        AppEvents.publisher().publish(DiagnosticEvent.warn(context, message, error));
    }

    static void error(EventContext context, String message, Throwable error) {
        AppEvents.publisher().publish(DiagnosticEvent.error(context, message, error));
    }

    static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ').replace('"', '\'');
    }

    private void emit(ConversationSession session, String message) {
        Objects.requireNonNull(session, "session");
        debugEvent(EventContext.of(session, "Session"), message);
    }
}
