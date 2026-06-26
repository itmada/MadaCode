package madacode.tool.access;

import madacode.tool.ToolNames;

import java.util.Objects;
import java.util.Set;

/**
 * Tool capability profile for one runtime role: what that role may ever use, and
 * which of those tools are exposed directly to the model (without a deferred
 * {@code tool_search} round-trip).
 *
 * <p>Three roles produce a profile:
 * <ul>
 *   <li><b>main / control session</b> &rarr; {@link #unrestricted()}: every tool is
 *       allowed; core tools are exposed directly and the rest are deferred.</li>
 *   <li><b>sub-agent</b> &rarr; {@link #subAgentUnrestricted} or
 *       {@link #subAgentRestrictedAllowList}: an allow/deny list from the agent definition.</li>
 *   <li><b>long-running worker</b> &rarr; {@link #explicitAllowList}: an explicit
 *       capability set built by the long-running workflow policy.</li>
 * </ul>
 *
 * <p>The {@link CapabilityMode} is explicit on purpose. An empty
 * {@code allowedTools} on an {@link CapabilityMode#EXPLICIT_ALLOW_LIST} profile
 * means "this role may use nothing" (e.g. a worker that has already reported),
 * which is the opposite of an unrestricted role. Call sites should choose a
 * factory whose name says which meaning they intend.
 */
public record ToolCapabilityProfile(
        String id,
        CapabilityMode mode,
        Set<String> allowedTools,
        Set<String> disallowedTools,
        boolean excludeRecursiveAgent) {

    public enum CapabilityMode { UNRESTRICTED, EXPLICIT_ALLOW_LIST }

    /**
     * Tools exposed directly to unrestricted roles. Other allowed tools remain
     * deferred and must be loaded through tool_search before use. tool_search is
     * included here as the bootstrap entry point for the deferred-tool directory.
     */
    private static final Set<String> UNRESTRICTED_DIRECT_TOOLS = Set.of(
            ToolNames.BASH,
            ToolNames.FILE_READ,
            ToolNames.FILE_WRITE,
            ToolNames.FILE_EDIT,
            ToolNames.GLOB,
            ToolNames.GREP,
            ToolNames.UPDATE_PLAN,
            ToolNames.ASK_USER_QUESTION,
            ToolNames.TOOL_SEARCH
    );

    private static final ToolCapabilityProfile UNRESTRICTED =
            new ToolCapabilityProfile("main", CapabilityMode.UNRESTRICTED, Set.of(), Set.of(), false);

    public ToolCapabilityProfile {
        id = id == null || id.isBlank() ? "agent" : id.strip();
        mode = mode == null ? CapabilityMode.EXPLICIT_ALLOW_LIST : mode;
        allowedTools = Set.copyOf(Objects.requireNonNullElse(allowedTools, Set.of()));
        disallowedTools = Set.copyOf(Objects.requireNonNullElse(disallowedTools, Set.of()));
    }

    /** Every tool is allowed; core tools are exposed directly, the rest are deferred. */
    public static ToolCapabilityProfile unrestricted() {
        return UNRESTRICTED;
    }

    /**
     * A restricted profile whose usable tools are exactly {@code allowedTools}.
     * An empty set means the role may use nothing.
     */
    public static ToolCapabilityProfile explicitAllowList(
            String id, Set<String> allowedTools, boolean excludeRecursiveAgent) {
        return new ToolCapabilityProfile(
                id, CapabilityMode.EXPLICIT_ALLOW_LIST, allowedTools, Set.of(), excludeRecursiveAgent);
    }

    /**
     * Default profile for a sub-agent definition with no allow-list: it inherits the
     * full tool directory and is only narrowed by {@code disallowedTools}. Sub-agents
     * may never spawn further sub-agents.
     */
    public static ToolCapabilityProfile subAgentUnrestricted(String id, Set<String> disallowedTools) {
        return new ToolCapabilityProfile(
                id, CapabilityMode.UNRESTRICTED, Set.of(), disallowedTools, true);
    }

    /**
     * Profile for a sub-agent definition with an explicit allow-list. An empty set
     * means the sub-agent may use no tools.
     */
    public static ToolCapabilityProfile subAgentRestrictedAllowList(
            String id, Set<String> allowedTools, Set<String> disallowedTools) {
        return new ToolCapabilityProfile(
                id, CapabilityMode.EXPLICIT_ALLOW_LIST, allowedTools, disallowedTools, true);
    }

    public boolean isRestricted() {
        return mode == CapabilityMode.EXPLICIT_ALLOW_LIST;
    }

    /** Whether this role may ever use the tool (capability boundary, before exposure). */
    public boolean allows(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return false;
        }
        if (excludeRecursiveAgent && ToolNames.AGENT.equals(toolName)) {
            return false;
        }
        if (disallowedTools.contains(toolName)) {
            return false;
        }
        return mode == CapabilityMode.UNRESTRICTED || allowedTools.contains(toolName);
    }

    /**
     * Whether this role exposes the tool directly (no deferred search). Restricted
     * roles expose their explicit allow-list directly; unrestricted roles expose
     * the core direct-tool tier and defer the rest.
     */
    public boolean exposesDirectly(String toolName) {
        if (!allows(toolName)) {
            return false;
        }
        return isRestricted()
                ? allowedTools.contains(toolName)
                : UNRESTRICTED_DIRECT_TOOLS.contains(toolName);
    }
}
