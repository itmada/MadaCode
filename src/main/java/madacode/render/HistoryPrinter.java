package madacode.render;

import madacode.core.model.ContentBlock;
import madacode.core.model.FinishReason;
import madacode.core.model.Message;
import madacode.core.model.MessageRole;
import madacode.render.tool.ToolCardWriter;
import madacode.render.tool.ToolDisplay;
import madacode.render.tool.ToolDisplayRegistry;
import madacode.render.tool.ToolActivitySkip;
import madacode.tui.Screen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static madacode.tui.theme.Tk.*;

/**
 * One-shot renderer for replaying a saved message history to the screen.
 * Not a SessionListener — walks the message list synchronously and writes
 * to scrollback. Produces output that matches live JLine echo byte-for-byte
 * for USER messages.
 */
public final class HistoryPrinter {

    private final Screen screen;
    private final ExpandableHistory expandableHistory;
    private final ToolDisplayRegistry toolDisplays = ToolDisplayRegistry.defaults();

    public HistoryPrinter(Screen screen, ExpandableHistory expandableHistory) {
        this.screen = Objects.requireNonNull(screen, "screen");
        this.expandableHistory = expandableHistory;
    }

    public void printAll(List<Message> messages) {
        Map<String, ContentBlock.ToolUseBlock> pending = new LinkedHashMap<>();
        for (Message message : messages) {
            if (message.isControllerEvent()) {
                continue;
            }
            for (ContentBlock block : message.contentBlocks()) {
                renderBlock(message.role(), block, pending);
            }
        }
    }

    public void printFrom(List<Message> messages, int fromIndex) {
        if (fromIndex < 0 || fromIndex >= messages.size()) return;
        Map<String, ContentBlock.ToolUseBlock> pending = new LinkedHashMap<>();
        for (int i = fromIndex; i < messages.size(); i++) {
            Message message = messages.get(i);
            if (message.isControllerEvent()) {
                continue;
            }
            for (ContentBlock block : message.contentBlocks()) {
                renderBlock(message.role(), block, pending);
            }
        }
    }

    private void renderBlock(MessageRole role,
                              ContentBlock block,
                              Map<String, ContentBlock.ToolUseBlock> pending) {
        switch (block) {
            case ContentBlock.TextBlock t -> renderText(role, t.text());
            case ContentBlock.TerminalBlock t -> renderTerminal(t.message(), t.reason());
            case ContentBlock.ThinkingBlock t -> { /* not rendered in scrollback */ }
            case ContentBlock.ToolUseBlock tu -> pending.put(tu.id(), tu);
            case ContentBlock.ToolResultBlock tr -> {
                ContentBlock.ToolUseBlock tu = pending.remove(tr.toolUseId());
                if (tu == null) return;
                ToolDisplay display = toolDisplays.renderResult(
                        tu.name(), tu.input(), tr.success(), tr.content(), tr.durationMs());
                if (!tr.success()) {
                    ToolDisplay compact = ToolActivitySkip.compactDisplay(display, tr.content());
                    if (compact != null) {
                        display = compact;
                    }
                }
                ToolCardWriter.write(screen, display, expandableHistory);
            }
        }
    }

    private void renderTerminal(String message, FinishReason reason) {
        if (reason == FinishReason.CANCELLED || reason == FinishReason.PERMISSION_CANCELLED) {
            screen.commitBlock(failure(message));
        } else {
            screen.commitBlock(errorTag("error") + " " + message);
        }
    }

    private void renderText(MessageRole role, String text) {
        switch (role) {
            case USER -> screen.commitBlock(UserInputRenderer.lines(text));
            case ASSISTANT -> {
                MarkdownRenderer md = new MarkdownRenderer();
                md.append(text);
                int width = screen.width();
                List<String> lines = new ArrayList<>();
                String line;
                while ((line = md.renderLine(width)) != null) {
                    lines.add(line);
                }
                String tail = md.flushRemaining(width);
                if (tail != null && !tail.isEmpty()) {
                    lines.add(tail);
                }
                screen.commitBlock(lines);
            }
            case SYSTEM -> screen.commitBlock(dim(text));
        }
    }

}
