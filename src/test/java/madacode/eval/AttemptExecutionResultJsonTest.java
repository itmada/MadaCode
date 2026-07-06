package madacode.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AttemptExecutionResultJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesStableContainerOutcomeDtoShape() throws Exception {
        String json = """
                {
                  "schemaVersion": "spike-1",
                  "caseId": "spike-noop",
                  "mode": "common",
                  "executionStatus": "COMPLETED",
                  "terminalSummary": "COMPLETED",
                  "detail": "ok",
                  "finalText": "done",
                  "metrics": {
                    "controlIterations": 0,
                    "workerIterations": 0,
                    "totalIterations": 0,
                    "workerCycles": 0,
                    "toolCalls": 0,
                    "tokenUsage": {
                      "inputTokens": 0,
                      "outputTokens": 0,
                      "cacheCreationTokens": 0,
                      "cacheReadTokens": 0,
                      "totalTokens": 0
                    }
                  },
                  "apiFailure": null,
                  "quiescent": true,
                  "trace": {
                    "invocations": [],
                    "fileEffects": [],
                    "userTurns": [],
                    "assistantTurns": [],
                    "finalText": "done",
                    "metrics": {
                      "controlIterations": 0,
                      "workerIterations": 0,
                      "totalIterations": 0,
                      "workerCycles": 0,
                      "toolCalls": 0,
                      "tokenUsage": {
                        "inputTokens": 0,
                        "outputTokens": 0,
                        "cacheCreationTokens": 0,
                        "cacheReadTokens": 0,
                        "totalTokens": 0
                      }
                    }
                  },
                  "diagnostics": ["spike"]
                }
                """;

        AttemptExecutionResultJson result =
                mapper.readValue(json, AttemptExecutionResultJson.class);

        assertEquals(AttemptExecutionResultJson.SCHEMA_VERSION, result.schemaVersion());
        assertEquals("spike-noop", result.caseId());
        assertEquals("COMPLETED", result.executionStatus());
        assertTrue(result.quiescent());
        assertEquals("done", result.trace().finalText());
    }
}
