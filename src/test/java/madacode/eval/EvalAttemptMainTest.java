package madacode.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalAttemptMainTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void noModelEntrypointRunsFromClosedInputDto() throws Exception {
        EvalCase evalCase = new EvalCase(
                "entry-no-model",
                "desc",
                "common",
                "default",
                List.of("selftest"),
                "do nothing",
                false,
                1,
                1,
                1,
                1,
                30,
                30,
                1024,
                "PASS",
                EvalChecks.NONE,
                List.of());
        EvalAttemptInputJson input = new EvalAttemptInputJson(
                EvalAttemptInputJson.SCHEMA_VERSION,
                evalCase,
                1,
                EvalAttemptInputJson.MODE_NO_MODEL,
                "/workspace",
                "",
                List.of("unit-test"));

        String json = mapper.writeValueAsString(input);
        assertFalse(json.contains("verify.sh"));
        assertFalse(json.contains("\"empty\""));
        assertFalse(json.contains("gatingOrDefault"));

        Files.createDirectories(tempDir.resolve("workspace"));
        AttemptExecutionResultJson result =
                EvalAttemptMain.runForTest(input, tempDir.resolve("workspace"));

        assertEquals("entry-no-model", result.caseId());
        assertEquals("common", result.mode());
        assertEquals(EvalResult.ExecutionStatus.COMPLETED.name(), result.executionStatus());
        assertTrue(result.quiescent());
        assertTrue(result.diagnostics().contains("unit-test"));
    }

    @Test
    void inputWireDtoRoundTripsChecksWithoutDomainAccessors() throws Exception {
        EvalCase evalCase = new EvalCase(
                "entry-checks",
                "desc",
                "common",
                "default",
                List.of("safety"),
                "do nothing",
                false,
                1,
                2,
                3,
                4,
                30,
                30,
                1024,
                "PASS",
                new EvalChecks(
                        new TrajectoryChecks(List.of("file_read"), List.of("bash"), List.of("src/Main.java"), true, true),
                        new EfficiencyChecks(5, 100, false),
                        new DialogChecks(true, "clear answer", false),
                        new SafetyChecks(true, true, List.of("secrets/decoy.txt"), true)),
                List.of(new ConversationTurn("do nothing", ConversationTurn.Trigger.ALWAYS)));
        EvalAttemptInputJson input = new EvalAttemptInputJson(
                EvalAttemptInputJson.SCHEMA_VERSION,
                evalCase,
                1,
                EvalAttemptInputJson.MODE_NO_MODEL,
                "/workspace",
                "",
                List.of());

        String json = mapper.writeValueAsString(input);
        assertTrue(json.contains("\"allowedTools\""));
        assertTrue(json.contains("\"decoyFiles\""));
        assertFalse(json.contains("gatingOrDefault"));
        assertFalse(json.contains("\"empty\""));

        EvalAttemptInputJson hydrated = mapper.readValue(json, EvalAttemptInputJson.class);
        EvalCase roundTripped = hydrated.evalCaseDomain();

        assertEquals(List.of("file_read"), roundTripped.checks().trajectory().allowedTools());
        assertEquals(100, roundTripped.checks().efficiency().maxTokens());
        assertEquals("clear answer", roundTripped.checks().dialog().rubric());
        assertEquals(List.of("secrets/decoy.txt"), roundTripped.checks().safety().decoyFiles());
        assertEquals(ConversationTurn.Trigger.ALWAYS, roundTripped.conversation().getFirst().trigger());
    }

    @Test
    void runtimeSetupFailureIsNonQuiescentInfrastructureOutcome() throws Exception {
        EvalCase evalCase = new EvalCase(
                "entry-runtime-bad-config",
                "desc",
                "common",
                "default",
                List.of(),
                "do nothing",
                false,
                1,
                1,
                1,
                1,
                30,
                30,
                1024,
                null,
                EvalChecks.NONE,
                List.of());
        EvalAttemptInputJson input = new EvalAttemptInputJson(
                EvalAttemptInputJson.SCHEMA_VERSION,
                evalCase,
                1,
                EvalAttemptInputJson.MODE_RUNTIME,
                tempDir.resolve("workspace").toString(),
                "{}",
                List.of());

        Files.createDirectories(tempDir.resolve("workspace"));
        AttemptExecutionResultJson result =
                EvalAttemptMain.runForTest(input, tempDir.resolve("workspace"));

        assertEquals(EvalResult.ExecutionStatus.CRASHED.name(), result.executionStatus());
        assertEquals("INFRA_ERROR", result.terminalSummary());
        assertFalse(result.quiescent());
    }
}
