package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.tool.Tool;
import madacode.tool.ToolNames;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Auto-allows built-in filesystem read tools whose targets stay inside the
 * working directory or an additional trusted root.
 *
 * <p>This rule is deliberately limited to workspace filesystem inspection. A
 * tool being {@link Tool#isReadOnly()} is not enough to make it a scope decision:
 * network, MCP, delegation, and session tools must flow through capability or
 * posture rules instead.
 */
public final class FilesystemReadPermissionRule implements PermissionRule {

    public static final String SOURCE = "filesystem_read";

    private static final Set<String> FILESYSTEM_READ_TOOLS = Set.of(
            ToolNames.FILE_READ,
            ToolNames.GLOB,
            ToolNames.GREP);

    private final List<Path> trustedRoots;

    public FilesystemReadPermissionRule() {
        this(List.of());
    }

    public FilesystemReadPermissionRule(List<Path> trustedRoots) {
        this.trustedRoots = List.copyOf(trustedRoots);
    }

    @Override
    public PermissionLayer layer() {
        return PermissionLayer.SCOPE;
    }

    @Override
    public Optional<PermissionDecision> evaluate(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        if (!tool.isReadOnly() || !FILESYSTEM_READ_TOOLS.contains(tool.name())) {
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
