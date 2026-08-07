package madacode.eval;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves a case's {@code mode} to its {@link ModeLauncher}. The single extension point
 * for workflow modes: register a launcher here and every case tagged with that mode id
 * routes through it — no other eval code changes.
 *
 * <p>★ When plan mode is refactored into a top-level {@code plan-and-execute} workflow,
 * register a {@code PlanExecuteModeLauncher} here; nothing else in the framework changes.
 */
public final class ModeLauncherRegistry {

    private final Map<String, ModeLauncher> launchers = new LinkedHashMap<>();

    public ModeLauncherRegistry register(ModeLauncher launcher) {
        if (launcher == null || launcher.modeId() == null || launcher.modeId().isBlank()) {
            throw new IllegalArgumentException("launcher and mode id must not be blank");
        }
        String modeId = launcher.modeId().strip().toLowerCase(java.util.Locale.ROOT);
        if (launchers.putIfAbsent(modeId, launcher) != null) {
            throw new IllegalArgumentException("duplicate launcher for mode '" + modeId + "'");
        }
        return this;
    }

    public ModeLauncher resolve(String modeId) {
        String normalized = modeId == null ? "" : modeId.strip().toLowerCase(java.util.Locale.ROOT);
        ModeLauncher launcher = launchers.get(normalized);
        if (launcher == null) {
            throw new IllegalArgumentException(
                    "no launcher registered for mode '" + modeId + "'; known modes: " + launchers.keySet());
        }
        return launcher;
    }

    /** The default registry wiring the modes that exist today. */
    public static ModeLauncherRegistry defaults() {
        return new ModeLauncherRegistry()
                .register(new CommonModeLauncher())
                .register(new LongRunningModeLauncher())
                .register(new ClaudeCodeModeLauncher());
    }
}
