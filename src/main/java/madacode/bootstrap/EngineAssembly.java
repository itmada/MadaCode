package madacode.bootstrap;

import madacode.core.engine.QueryEngine;
import madacode.hook.HookManager;
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
        QueryEngine engine = QueryEngine.builder(
                        environment.api(), tools.registry(),
                        SystemPromptBuilder.builder()
                                .memoryLoader(tools.memory())
                                .skillRegistry(tools.skillRegistry())
                                .build(),
                        permission)
                .diagnosticEvents(environment.diagnosticEvents())
                .compactPlanner(compaction)
                .toolAccessResolver(tools.toolAccessResolver())
                .hookManager(new HookManager(environment.paths().globalHooksFile()))
                .build();
        return new EngineRuntime(engine, permission, compaction);
    }

    private static CompactPlanner createCompaction(EnvironmentRuntime environment) {
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
