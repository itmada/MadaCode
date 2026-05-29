package madacode.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

class EvalRunnerTest {

    @Test
    void runAllScenarios() throws Exception {
        Path scenariosDir = Path.of("src/test/resources/eval");
        EvalRunner runner = new EvalRunner();
        List<EvalResult> results = runner.runAll(scenariosDir);

        assertFalse(results.isEmpty(), "Should have at least one scenario");

        // All scenarios should pass
        for (EvalResult result : results) {
            assertTrue(result.passed(),
                    result.scenario() + " failed: " + String.join("; ", result.failures()));
        }

        // Print report
        System.out.println(EvalRunner.generateReport(results));
    }

    @Test
    void scenarioSimpleRead() {
        EvalScenario scenario = new EvalScenario(
                "simple read",
                "read pom.xml",
                List.of(
                        new MockApiResponse("reading",
                                List.of(new MockApiResponse.ToolCallStub("t1", "file_read",
                                        new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("file_path", "pom.xml")))),
                        new MockApiResponse("result: pom.xml content", List.of())),
                List.of(EvalAssertion.toolCalled("file_read"),
                        EvalAssertion.finishReason("COMPLETED")));

        EvalRunner runner = new EvalRunner();
        EvalResult result = runner.run(scenario);
        assertTrue(result.passed(), "should pass: " + String.join("; ", result.failures()));
        assertEquals("simple read", result.scenario());
    }

    @Test
    void scenarioTaskCreate() {
        EvalScenario scenario = new EvalScenario(
                "task create",
                "break this down",
                List.of(
                        new MockApiResponse("breaking down",
                                List.of(new MockApiResponse.ToolCallStub("t1", "TaskCreate",
                                        new com.fasterxml.jackson.databind.ObjectMapper()
                                                .createObjectNode()
                                                .putPOJO("tasks", List.of(
                                                        java.util.Map.of("title", "Step 1"),
                                                        java.util.Map.of("title", "Step 2")))))),
                        new MockApiResponse("tasks created successfully", List.of())),
                List.of(EvalAssertion.toolCalled("TaskCreate"),
                        EvalAssertion.finishReason("COMPLETED")));

        EvalRunner runner = new EvalRunner();
        EvalResult result = runner.run(scenario);
        assertTrue(result.passed(), "should pass: " + String.join("; ", result.failures()));
    }

    @Test
    void scenarioOutputContainsAssertion() {
        EvalScenario scenario = new EvalScenario(
                "output check",
                "say hello",
                List.of(
                        new MockApiResponse("Hello, world!", List.of())),
                List.of(EvalAssertion.outputContains("Hello")));

        EvalRunner runner = new EvalRunner();
        EvalResult result = runner.run(scenario);
        assertTrue(result.passed(), String.join("; ", result.failures()));
    }

    @Test
    void failingAssertionCapturesReason() {
        EvalScenario scenario = new EvalScenario(
                "failing case",
                "do something",
                List.of(
                        new MockApiResponse("wrong output", List.of())),
                List.of(EvalAssertion.outputContains("EXPECTED_BUT_MISSING")));

        EvalRunner runner = new EvalRunner();
        EvalResult result = runner.run(scenario);
        assertFalse(result.passed());
        assertEquals(1, result.failures().size());
        assertTrue(result.failures().getFirst().contains("EXPECTED_BUT_MISSING"));
    }

    @Test
    void reportIncludesPassingAndFailing() {
        List<EvalResult> results = List.of(
                EvalResult.pass("scenario A", 10, 2, 1),
                EvalResult.pass("scenario B", 5, 1, 0),
                EvalResult.fail("scenario C", 15, 3, 2, List.of("tool X not called")));

        String report = EvalRunner.generateReport(results);
        assertTrue(report.contains("scenario A"));
        assertTrue(report.contains("PASS"));
        assertTrue(report.contains("FAIL"));
        assertTrue(report.contains("tool X not called"));
        assertTrue(report.contains("2/3"));
    }
}
