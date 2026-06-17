package madacode.tool;

import madacode.core.session.ConversationSession;
import madacode.tool.access.ToolAccessResolver;
import madacode.tool.access.ToolAccessScope;

import java.util.Collection;

public final class ToolVisibility {

    private static final ToolAccessResolver RESOLVER = ToolAccessResolver.defaultResolver();

    private ToolVisibility() {}

    public static boolean isAlwaysVisible(String toolName) {
        return RESOLVER.isAlwaysVisible(toolName);
    }

    public static VisibleTools visibleToolsForSession(Collection<Tool<?>> tools,
                                                      ConversationSession session) {
        return RESOLVER.visibleTools(tools, session);
    }

    public static VisibleTools empty() {
        return new VisibleTools(java.util.List.of());
    }

    public static String executionDenialReason(Tool<?> tool, ConversationSession session) {
        return RESOLVER.executionDenialReason(tool, session);
    }

    public static String exposedToolDenialReason(Tool<?> tool, madacode.core.engine.ToolUseContext context) {
        ToolAccessScope scope = context == null
                ? ToolAccessScope.unrestricted(null)
                : context.toolAccessScope();
        return RESOLVER.exposedToolDenialReason(tool, scope);
    }
}
