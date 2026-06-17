package madacode.tool.access;

import madacode.core.session.ConversationSession;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime state used by the tool access layer.
 *
 * <p>The session owns persistent deferred-tool loading. The scope adds
 * per-runtime overlays such as inherited loaded tools for sub-agents and the
 * exact tool snapshot exposed to one model request.
 */
public record ToolAccessScope(
        ConversationSession session,
        AgentToolProfile agentToolProfile,
        Set<String> preloadedToolNames,
        Set<String> exposedToolNames) {

    public ToolAccessScope {
        agentToolProfile = agentToolProfile == null
                ? AgentToolProfile.unrestricted()
                : agentToolProfile;
        preloadedToolNames = Set.copyOf(Objects.requireNonNullElse(preloadedToolNames, Set.of()));
        exposedToolNames = exposedToolNames == null ? null : Set.copyOf(exposedToolNames);
    }

    public static ToolAccessScope unrestricted(ConversationSession session) {
        return new ToolAccessScope(session, AgentToolProfile.unrestricted(), Set.of(), null);
    }

    public static ToolAccessScope forAgent(
            ConversationSession session,
            AgentToolProfile profile,
            Collection<String> preloadedToolNames) {
        return new ToolAccessScope(session, profile, names(preloadedToolNames), null);
    }

    public ToolAccessScope withExposedToolNames(Collection<String> toolNames) {
        return new ToolAccessScope(session, agentToolProfile, preloadedToolNames, names(toolNames));
    }

    public boolean hasExposedToolSnapshot() {
        return exposedToolNames != null;
    }

    public boolean wasToolExposed(String canonicalToolName) {
        return exposedToolNames == null || exposedToolNames.contains(canonicalToolName);
    }

    public boolean isDeferredToolLoaded(String canonicalToolName) {
        if (canonicalToolName == null || canonicalToolName.isBlank()) {
            return false;
        }
        if (preloadedToolNames.contains(canonicalToolName)) {
            return true;
        }
        return session != null && session.loadedDeferredTools().contains(canonicalToolName);
    }

    public Set<String> loadedToolNamesSnapshot() {
        Set<String> loaded = new HashSet<>(preloadedToolNames);
        if (session != null) {
            loaded.addAll(session.loadedDeferredTools());
        }
        return Set.copyOf(loaded);
    }

    private static Set<String> names(Collection<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return Set.of();
        }
        return toolNames.stream()
                .filter(name -> name != null && !name.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
