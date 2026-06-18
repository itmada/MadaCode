package madacode.bootstrap;

import madacode.core.engine.QueryEngine;
import madacode.hook.HookManager;
import madacode.memory.MemoryLoader;
import madacode.permission.PermissionGate;
import madacode.prompt.SystemPromptBuilder;
import madacode.services.compact.CompactBudget;
import madacode.services.compact.CompactPlanner;
import madacode.services.compact.FullCompactStrategy;
import madacode.services.compact.MicroCompactStrategy;
import madacode.services.compact.TokenEstimator;

import java.util.List;

final class EngineAssembly {

    private EngineAssembly() {
    }

    static EngineRuntime create(
            EnvironmentRuntime environment,
            ToolRuntime tools,
            PermissionGate permission) {
        CompactPlanner compaction = createCompaction(environment);
        QueryEngine engine = configuredBuilder(
                        environment, tools, permission, tools.memory(), compaction)
                .hookManager(new HookManager(environment.paths().globalHooksFile()))
                .build();
        return new EngineRuntime(engine, permission, compaction);
    }

    /**
     * The shared {@link QueryEngine} configuration used by both the interactive runtime and
     * the headless eval runtime ({@code HeadlessAgentRuntime}). Centralizing it here means
     * the agent under eval gets the same system prompt (skills + memory), context compaction,
     * diagnostics, and tool-access policy as production — no drift. Callers layer on their own
     * specifics: production attaches hooks; eval caps iterations and passes a {@code null}
     * memory loader for reproducibility.
     */
    static QueryEngine.Builder configuredBuilder(
            EnvironmentRuntime environment,
            ToolRuntime tools,
            PermissionGate permission,
            MemoryLoader memory,
            CompactPlanner compaction) {
        return QueryEngine.builder(
                        environment.api(), tools.registry(),
                        SystemPromptBuilder.builder()
                                .memoryLoader(memory)
                                .skillRegistry(tools.skillRegistry())
                                .build(),
                        permission)
                .diagnosticEvents(environment.diagnosticEvents())
                .compactPlanner(compaction)
                .toolAccessResolver(tools.toolAccessResolver());
    }

    static CompactPlanner createCompaction(EnvironmentRuntime environment) {
        TokenEstimator estimator = new TokenEstimator();
        CompactBudget budget = CompactBudget.defaults();
        return new CompactPlanner(
                estimator,
                budget,
                List.of(
                        new MicroCompactStrategy(estimator),
                        new FullCompactStrategy(environment.api(), estimator, e -> {})));
    }
}
