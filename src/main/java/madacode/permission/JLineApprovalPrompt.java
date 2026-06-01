package madacode.permission;

import madacode.core.turn.CancellationToken;
import madacode.core.engine.ToolExecutor;
import madacode.render.turn.TurnRenderer;
import madacode.tool.Tool;
import madacode.tui.Screen;
import madacode.tui.Suspendable;
import madacode.tui.TerminalKeys;
import madacode.tui.widget.ApprovalPanel;

import org.jline.terminal.Terminal;
import org.jline.terminal.Attributes;
import org.jline.utils.AttributedString;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Approval prompt that uses inline permission rendering via
 * {@link TurnRenderer} when available, falling back to the
 * traditional {@link Screen#setLiveModal} path.
 */
public final class JLineApprovalPrompt implements UserApprovalPrompt {

    private static final List<ApprovalPanel.Action> ACTIONS = ApprovalPanel.defaultActions();

    private final Terminal terminal;
    private final Screen screen;
    private final Suspendable readerLock;
    private final Object promptLock = new Object();
    private final Consumer<String> onInterrupt;  // nullable; called on ESC/Ctrl+C to cancel the turn
    private volatile TurnRenderer turnRenderer;

    public JLineApprovalPrompt(Screen screen, Terminal terminal, Suspendable readerLock,
                               Consumer<String> onInterrupt) {
        this.screen      = Objects.requireNonNull(screen, "screen");
        this.terminal    = Objects.requireNonNull(terminal, "terminal");
        this.readerLock  = readerLock;
        this.onInterrupt = onInterrupt;
    }

    public JLineApprovalPrompt(Screen screen, Terminal terminal, Suspendable readerLock) {
        this(screen, terminal, readerLock, null);
    }

    public void setTurnRenderer(TurnRenderer turnRenderer) {
        this.turnRenderer = turnRenderer;
    }

    @Override
    public ApprovalResponse requestApproval(Tool<?> tool, String input) {
        synchronized (promptLock) {
            TurnRenderer tr = turnRenderer;
            if (tr != null) {
                String toolUseId = ToolExecutor.CURRENT_TOOL_USE_ID.get();
                if (toolUseId != null) {
                    return requestApprovalInline(tr, toolUseId);
                }
            }
            return requestApprovalModal(tool, input);
        }
    }

    // ---- inline path (TurnRenderer-based, no setLiveModal) ---------------

    /**
     * Inline approval using the TurnRenderer tool card.
     *
     * <p>Input ownership: the {@link InterruptController} ESC monitor owns the
     * terminal reader during a turn.  We borrow exclusive access via
     * {@link Suspendable#pause()} before reading keys, and return it in the
     * {@code finally} block.  Terminal raw mode is already set by
     * {@code InterruptController.beginTurn()} — we do NOT call
     * {@code enterRawMode()} again.
     */
    private ApprovalResponse requestApprovalInline(TurnRenderer tr, String toolUseId) {
        tr.beginPermission(toolUseId);

        int NUM_OPTIONS = ACTIONS.size();
        int selectedIdx = 0;

        ResourceScope scope = new ResourceScope(screen, terminal, readerLock, false, false);
        try {
            scope.pauseReader();
            scope.hideCursor();
            while (true) {
                TerminalKeys.KeyPress key;
                try {
                    key = TerminalKeys.readKey(terminal.reader());
                } catch (IOException e) {
                    tr.cancelPermission(toolUseId);
                    return ApprovalResponse.DENY;
                }
                switch (key.key()) {
                    case ENTER -> {
                        return responseForIndex(tr, toolUseId, selectedIdx);
                    }
                    case UP, LEFT -> {
                        selectedIdx = (selectedIdx - 1 + NUM_OPTIONS) % NUM_OPTIONS;
                        tr.setPermissionSelected(toolUseId, selectedIdx);
                    }
                    case DOWN, RIGHT -> {
                        selectedIdx = (selectedIdx + 1) % NUM_OPTIONS;
                        tr.setPermissionSelected(toolUseId, selectedIdx);
                    }
                    case ESCAPE, CTRL_C, EOF -> {
                        tr.cancelPermission(toolUseId);
                        fireInterrupt(CancellationToken.REASON_PERMISSION_DENIED);
                        return ApprovalResponse.DENY;
                    }
                    default -> {
                        ApprovalResponse hotkeyResponse = responseForHotkey(key);
                        if (hotkeyResponse != null) {
                            boolean denied = hotkeyResponse == ApprovalResponse.DENY;
                            tr.resolvePermission(toolUseId, denied);
                            return hotkeyResponse;
                        }
                    }
                }
            }
        } finally {
            scope.close();
        }
    }

    private static ApprovalResponse responseForIndex(TurnRenderer tr, String toolUseId, int idx) {
        ApprovalPanel.Decision decision = ACTIONS.get(idx).decision();
        boolean denied = decision == ApprovalPanel.Decision.DENY;
        tr.resolvePermission(toolUseId, denied);
        return responseFor(decision);
    }

    // ---- legacy modal path ------------------------------------------------

    private ApprovalResponse requestApprovalModal(Tool<?> tool, String input) {
        ResourceScope scope = new ResourceScope(screen, terminal, readerLock, true, true);
        try {
            scope.pauseReader();
            scope.enterRawMode();
            scope.hideCursor();
            int selectedIdx = 0;
            while (true) {
                scope.showModal(toAnsiLines(ApprovalPanel.render(
                        buildApprovalView(tool, input, selectedIdx), screen.width())));
                TerminalKeys.KeyPress key = TerminalKeys.readKey(terminal.reader());
                switch (key.key()) {
                    case ENTER -> {
                        return responseFor(ACTIONS.get(selectedIdx).decision());
                    }
                    case LEFT, UP -> {
                        selectedIdx = Math.floorMod(selectedIdx - 1, ACTIONS.size());
                    }
                    case RIGHT, DOWN -> {
                        selectedIdx = Math.floorMod(selectedIdx + 1, ACTIONS.size());
                    }
                    case ESCAPE, CTRL_C, EOF -> {
                        fireInterrupt(CancellationToken.REASON_PERMISSION_DENIED);
                        return ApprovalResponse.DENY;
                    }
                    default -> {
                        ApprovalResponse hotkeyResponse = responseForHotkey(key);
                        if (hotkeyResponse != null) {
                            return hotkeyResponse;
                        }
                    }
                }
            }
        } catch (IOException e) {
            fireInterrupt(CancellationToken.REASON_PERMISSION_DENIED);
            return ApprovalResponse.DENY;
        } finally {
            scope.close();
        }
    }

    private ApprovalPanel.ApprovalRequestView buildApprovalView(
            Tool<?> tool, String input, int selectedIdx) {
        String subject = tool == null ? "" : Objects.requireNonNullElse(tool.name(), "");
        String detail = Objects.requireNonNullElse(input, "");
        return ApprovalPanel.modalView(subject, detail, selectedIdx);
    }

    private static List<String> toAnsiLines(List<AttributedString> lines) {
        List<String> rendered = new ArrayList<>(lines.size());
        for (AttributedString line : lines) {
            rendered.add(line.toAnsi());
        }
        return rendered;
    }

    private void fireInterrupt(String reason) {
        if (onInterrupt != null) onInterrupt.accept(reason);
    }

    private static ApprovalResponse responseForHotkey(TerminalKeys.KeyPress key) {
        if (!key.isPrintable()) {
            return null;
        }
        int ch = Character.toLowerCase(key.ch());
        for (ApprovalPanel.Action action : ACTIONS) {
            if (!action.hotkey().isBlank()
                    && action.hotkey().equalsIgnoreCase(Character.toString((char) ch))) {
                return responseFor(action.decision());
            }
        }
        return null;
    }

    private static ApprovalResponse responseFor(ApprovalPanel.Decision decision) {
        return switch (decision) {
            case DENY -> ApprovalResponse.DENY;
            case ALLOW_ONCE -> ApprovalResponse.ALLOW_ONCE;
            case ALLOW_SESSION -> ApprovalResponse.ALLOW_SESSION;
        };
    }

    private static final class ResourceScope {
        private final Screen screen;
        private final Terminal terminal;
        private final Suspendable readerLock;
        private final boolean manageRawMode;
        private final boolean manageModal;
        private boolean readerPaused;
        private boolean cursorHidden;
        private boolean modalShown;
        private Attributes previousAttributes;

        private ResourceScope(
                Screen screen,
                Terminal terminal,
                Suspendable readerLock,
                boolean manageRawMode,
                boolean manageModal) {
            this.screen = screen;
            this.terminal = terminal;
            this.readerLock = readerLock;
            this.manageRawMode = manageRawMode;
            this.manageModal = manageModal;
        }

        private void pauseReader() {
            if (readerLock != null && !readerPaused) {
                readerLock.pause();
                readerPaused = true;
            }
        }

        private void enterRawMode() {
            if (manageRawMode && previousAttributes == null) {
                previousAttributes = terminal.enterRawMode();
            }
        }

        private void hideCursor() {
            if (!cursorHidden) {
                screen.setCursorVisible(false);
                cursorHidden = true;
            }
        }

        private void showModal(List<String> lines) {
            screen.setLiveModal(lines);
            modalShown = true;
        }

        private void close() {
            try {
                if (manageModal && modalShown) {
                    screen.clearLiveModal();
                }
            } finally {
                try {
                    if (cursorHidden) {
                        screen.setCursorVisible(true);
                    }
                } finally {
                    try {
                        if (manageRawMode && previousAttributes != null) {
                            terminal.setAttributes(previousAttributes);
                        }
                    } finally {
                        if (readerPaused) {
                            readerLock.resume();
                        }
                    }
                }
            }
        }
    }
}
