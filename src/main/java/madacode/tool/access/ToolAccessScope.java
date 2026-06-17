package madacode.tool.access;

import madacode.core.session.ConversationSession;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Runtime state used by the tool access layer.
 *
 * <p>The session owns persistent deferred-tool loading. The scope adds per-runtime
 * overlays: an optional explicit capability profile (for sub-agents), inherited
 * loaded tools, and the exact tool snapshot exposed to one model request.
 *
 * <p>When {@code explicitProfile} is {@code null} the resolver derives the effective
 * capability from the session's workflow role, so a scope built with a plain
 * {@link #forSession} on e.g. a long-running worker session is still restricted —
 * the restriction follows the session, not the construction site.
 *
 * <p>Assembly flow:
 * <ol>
 *   <li>Root turns start with {@link #forSession}: session and workflow role only.</li>
 *   <li>Sub-agents use {@link #forSubAgent}: explicit profile plus the parent's
 *       loaded deferred-tool snapshot.</li>
 *   <li>Each model request calls {@link #withRequestExposedToolNames}: the exact
 *       tool declarations sent to the model become the execution boundary for that
 *       tool batch.</li>
 * </ol>
 */
public record ToolAccessScope(
        ConversationSession session,
        ToolCapabilityProfile explicitProfile,
        Set<String> preloadedToolNames,
        Set<String> exposedToolNames) {

    public ToolAccessScope {
        preloadedToolNames = Set.copyOf(Objects.requireNonNullElse(preloadedToolNames, Set.of()));
        exposedToolNames = exposedToolNames == null ? null : Set.copyOf(exposedToolNames);
    }

    /** Scope whose capability is derived from the session's workflow role. */
    public static ToolAccessScope forSession(ConversationSession session) {
        return new ToolAccessScope(session, null, Set.of(), null);
    }

    /** Scope with an explicit sub-agent capability profile plus inherited loaded tools. */
    public static ToolAccessScope forSubAgent(
            ConversationSession session,
            ToolCapabilityProfile profile,
            Collection<String> preloadedToolNames) {
        return new ToolAccessScope(session, profile, names(preloadedToolNames), null);
    }

    public ToolAccessScope withRequestExposedToolNames(Collection<String> toolNames) {
        return new ToolAccessScope(session, explicitProfile, preloadedToolNames, names(toolNames));
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
