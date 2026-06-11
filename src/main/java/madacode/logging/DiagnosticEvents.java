package madacode.logging;

import madacode.core.model.FinishReason;
import madacode.core.session.ConversationSession;
import madacode.permission.PermissionDecision;

import java.nio.file.Path;
import java.util.Collection;

public interface DiagnosticEvents {

    void turnStarted(ConversationSession session, Integer maxIterations);

    void modelIterationCompleted(
            ConversationSession session,
            int iteration,
            long durationMs,
            int toolUseCount);

    void turnCompleted(
            ConversationSession session,
            FinishReason finishReason,
            int iterations,
            long durationMs);

    void toolValidationFailed(
            ConversationSession session,
            String toolName,
            Collection<String> errors);

    void permissionDecision(
            ConversationSession session,
            String toolName,
            PermissionDecision decision,
            long waitMs);

    void toolExecutionCompleted(
            ConversationSession session,
            String toolName,
            boolean success,
            long durationMs);

    void transcriptSaved(ConversationSession session, Path path);

    void transcriptLoaded(ConversationSession session, Path path);

    void apiRequest(String model, int messageCount, int maxTokens);

    void apiResponse(int statusCode, long durationMs);

    void apiModelResponseFull(
            String model,
            int statusCode,
            int lineCount,
            int charCount,
            Path path);

    void apiError(int statusCode, String bodyPreview);

    void apiToolInputStream(
            String toolName,
            String toolUseId,
            int deltaCount,
            int inputChars,
            boolean empty);

    void apiToolInputJsonParseFailed(
            String toolName,
            String toolUseId,
            int inputChars);

    void apiRetry(int attempt, int maxRetries, String type, long backoffMs);

    void apiFinalFailure(int attempts, String type, boolean retryable);

    static DiagnosticEvents noop() {
        return NoopDiagnosticEvents.INSTANCE;
    }
}
