package madacode.eval;

import madacode.core.session.ConversationSession;
import madacode.core.model.Message;
import madacode.core.engine.QueryEngine;
import madacode.core.turn.TurnResult;
import madacode.prompt.SystemPromptBuilder;
import madacode.tool.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class EvalRunner {

    private final ObjectMapper mapper;

    public EvalRunner() {
        this.mapper = new ObjectMapper();
    }

    public List<EvalResult> runAll(Path scenariosDir) throws IOException {
        List<EvalResult> results = new ArrayList<>();
        try (var paths = Files.newDirectoryStream(scenariosDir, "*.json")) {
            for (Path file : paths) {
                EvalScenario scenario = mapper.readValue(file.toFile(), EvalScenario.class);
                results.add(run(scenario));
            }
        }
        results.sort((a, b) -> a.scenario().compareTo(b.scenario()));
        return results;
    }

    EvalResult run(EvalScenario scenario) {
        MockApiClient mockApi = new MockApiClient(scenario.mockApiResponses());
        QueryEngine engine = createEngine(mockApi);
        ConversationSession session = new ConversationSession();

        long start = System.nanoTime();
        TurnResult turn = engine.runTurn(session, scenario.userInput());
        long durationMs = (System.nanoTime() - start) / 1_000_000;

        int toolCallCount = countToolCalls(session);
        List<String> failures = checkAssertions(scenario.assertions(), turn, session);
        boolean passed = failures.isEmpty();

        return passed
                ? EvalResult.pass(scenario.description(), durationMs, turn.iterations(), toolCallCount)
                : EvalResult.fail(scenario.description(), durationMs, turn.iterations(), toolCallCount, failures);
    }

    private QueryEngine createEngine(MockApiClient apiClient) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BashTool());
        registry.register(new FileReadTool());
        registry.register(new FileWriteTool());
        registry.register(new FileEditTool());
        registry.register(new GlobTool());
        registry.register(new GrepTool());
        registry.register(new PlanCreateTool());
        registry.register(new PlanGetTool());
        registry.register(new PlanListTool());
        registry.register(new PlanUpdateTool());
        registry.register(new TodoWriteTool());
        madacode.skill.SkillStateStore skillStore =
                new madacode.skill.SkillStateStore(
                        java.nio.file.Path.of(System.getProperty("user.home"),
                                ".mada/skills.json"));
        madacode.skill.SkillRegistry skillReg =
                new madacode.skill.SkillRegistry(skillStore);
        madacode.permission.PermissionGate gate = madacode.permission.PermissionGate.permissive();
        registry.register(new SkillTool(skillReg,
                new madacode.agent.AgentRunner(registry, apiClient, gate)));
        registry.register(new EnterPlanModeTool());
        registry.register(new ExitPlanModeTool());
        registry.register(new madacode.tool.LongRunStageUpdateTool());
        registry.register(new madacode.tool.LongRunTaskUpdateTool());

        return new QueryEngine(apiClient, registry,
                new SystemPromptBuilder(), gate);
    }

    private int countToolCalls(ConversationSession session) {
        int count = 0;
        for (Message message : session.messages()) {
            for (var block : message.contentBlocks()) {
                if (block instanceof madacode.core.model.ContentBlock.ToolUseBlock) {
                    count++;
                }
            }
        }
        return count;
    }

    private List<String> checkAssertions(
            List<EvalAssertion> assertions, TurnResult turn, ConversationSession session) {
        List<String> failures = new ArrayList<>();
        for (EvalAssertion a : assertions) {
            switch (a.type()) {
                case "tool_called" -> {
                    boolean found = session.messages().stream()
                            .flatMap(m -> m.contentBlocks().stream())
                            .anyMatch(b -> b instanceof madacode.core.model.ContentBlock.ToolUseBlock tb
                                    && tb.name().equals(a.expected()));
                    if (!found) {
                        failures.add("Expected tool " + a.expected() + " to be called");
                    }
                }
                case "output_contains" -> {
                    if (turn.finalText() == null || !turn.finalText().contains(a.expected())) {
                        failures.add("Expected output to contain '" + a.expected() + "'");
                    }
                }
                case "finish_reason" -> {
                    if (!turn.finishReason().name().equals(a.expected())) {
                        failures.add("Expected finish reason " + a.expected()
                                + " but was " + turn.finishReason().name());
                    }
                }
                case "iteration_count" -> {
                    int expected = Integer.parseInt(a.expected());
                    if (turn.iterations() != expected) {
                        failures.add("Expected " + expected + " iterations but was " + turn.iterations());
                    }
                }
            }
        }
        return failures;
    }

    public static String generateReport(List<EvalResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Eval Report\n\n");
        sb.append("| Scenario | Result | Duration | Iterations | Tool Calls |\n");
        sb.append("|----------|--------|----------|------------|------------|\n");

        long passed = 0;
        long totalMs = 0;
        for (EvalResult r : results) {
            sb.append("| ").append(r.scenario()).append(" | ")
                    .append(r.passed() ? "PASS" : "FAIL").append(" | ")
                    .append(r.durationMs()).append("ms | ")
                    .append(r.iterations()).append(" | ")
                    .append(r.toolCalls()).append(" |\n");
            if (r.passed()) passed++;
            totalMs += r.durationMs();
        }

        sb.append("\n**").append(passed).append("/").append(results.size())
                .append(" passed (").append(results.isEmpty() ? 0 : (100 * passed / results.size()))
                .append("%)** | Total: ").append(totalMs).append("ms");

        if (!results.isEmpty()) {
            sb.append(" | Avg: ").append(totalMs / results.size()).append("ms/scenario");
        }
        sb.append("\n");

        // Detail failures
        for (EvalResult r : results) {
            if (!r.passed()) {
                sb.append("\n### FAIL: ").append(r.scenario()).append("\n");
                for (String f : r.failures()) {
                    sb.append("- ").append(f).append("\n");
                }
            }
        }

        return sb.toString();
    }
}
