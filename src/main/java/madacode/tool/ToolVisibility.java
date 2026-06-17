package madacode.tool;

import madacode.core.session.ConversationSession;
import madacode.tool.access.ToolAccessResolver;

import java.util.Collection;

public final class ToolVisibility {

    private static final ToolAccessResolver RESOLVER = ToolAccessResolver.defaultResolver();

    private ToolVisibility() {}

    public static VisibleTools visibleToolsForSession(Collection<Tool<?>> tools,
                                                      ConversationSession session) {
        return RESOLVER.visibleTools(tools, session);
    }

    public static VisibleTools empty() {
        return new VisibleTools(java.util.List.of());
    }
}
