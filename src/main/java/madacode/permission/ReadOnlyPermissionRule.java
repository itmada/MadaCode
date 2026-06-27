package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.tool.Tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Auto-allows read-only tools whose filesystem targets fall within the
 * working directory or an additional trusted root.
 *
 * <p>Read-only tools without targets (e.g. {@code glob} with no path)
 * are always allowed.  Read-only tools with targets outside every
 * trusted root fall through to the next rule, ultimately reaching the
 * interactive prompt so the user can decide.
 *
 * <p>The gate is the sole authority for filesystem policy — tools must
 * never reject accesses themselves.
 */
public final class ReadOnlyPermissionRule implements PermissionRule {

    public static final String SOURCE = "read_only";

    @Override
    public PermissionLayer layer() {
        return PermissionLayer.SCOPE;
    }

    private final List<Path> trustedRoots;

    public ReadOnlyPermissionRule() {
        this(List.of());
    }

    public ReadOnlyPermissionRule(List<Path> trustedRoots) {
        this.trustedRoots = List.copyOf(trustedRoots);
    }

    @Override
    public Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        if (!tool.isReadOnly()) {
            return Optional.empty();
        }

        List<String> targets = tool.permissionTargets(input);
        if (targets.isEmpty()) {
            return Optional.of(PermissionDecision.allow(layer(), SOURCE));
        }

        Path workingDir = context.workingDirectory();
        for (String target : targets) {
            if (!FilesystemScope.withinRoots(target, workingDir, trustedRoots)) {
                return Optional.empty();
            }
        }

        return Optional.of(PermissionDecision.allow(layer(), SOURCE));
    }
}
