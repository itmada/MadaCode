package madacode.logging;

import madacode.core.model.FinishReason;
import madacode.core.session.ConversationSession;
import madacode.permission.PermissionDecision;

import java.nio.file.Path;
import java.util.Collection;

public final class NoopDiagnosticEvents implements DiagnosticEvents {

    static final NoopDiagnosticEvents INSTANCE = new NoopDiagnosticEvents();

    private NoopDiagnosticEvents() {
    }

    @Override
    public void turnStarted(ConversationSession session, Integer maxIterations) {
    }

    @Override
    public void modelIterationCompleted(
            ConversationSession session,
            int iteration,
            long durationMs,
            int toolUseCount) {
    }

    @Override
    public void turnCompleted(
            ConversationSession session,
            FinishReason finishReason,
            int iterations,
            long durationMs) {
    }

    @Override
    public void toolValidationFailed(
            ConversationSession session,
            String toolName,
            Collection<String> errors) {
    }

    @Override
    public void permissionDecision(
            ConversationSession session,
            String toolName,
            PermissionDecision decision,
            long waitMs) {
    }

    @Override
    public void toolExecutionCompleted(
            ConversationSession session,
            String toolName,
            boolean success,
            long durationMs) {
    }

    @Override
    public void transcriptSaved(ConversationSession session, Path path) {
    }

    @Override
    public void transcriptLoaded(ConversationSession session, Path path) {
    }

    @Override
    public void apiRequest(String model, int messageCount, int maxTokens) {
    }

    @Override
    public void apiResponse(int statusCode, long durationMs) {
    }

    @Override
    public void apiModelResponseFull(
            String model,
            int statusCode,
            int lineCount,
            int charCount,
            Path path) {
    }

    @Override
    public void apiError(int statusCode, String bodyPreview) {
    }

    @Override
    public void apiToolInputStream(
            String toolName,
            String toolUseId,
            int deltaCount,
            int inputChars,
            boolean empty) {
    }

    @Override
    public void apiToolInputJsonParseFailed(
            String toolName,
            String toolUseId,
            int inputChars) {
    }

    @Override
    public void apiRetry(int attempt, int maxRetries, String type, long backoffMs) {
    }

    @Override
    public void apiFinalFailure(int attempts, String type, boolean retryable) {
    }
}
