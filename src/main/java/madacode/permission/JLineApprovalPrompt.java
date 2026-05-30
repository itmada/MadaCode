package madacode.permission;

import madacode.core.CancellationToken;
import madacode.core.ToolExecutor;
import madacode.render.turn.TurnRenderer;
import madacode.tool.Tool;
import madacode.tui.Screen;
import madacode.tui.Suspendable;
import madacode.tui.TerminalKeys;
import madacode.tui.inline.InlineChoicePrompt;
import madacode.tui.widget.ApprovalPanel;
import madacode.tui.widget.ChoicePrompt;

import org.jline.terminal.Terminal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Approval prompt that uses inline permission rendering via
 * {@link TurnRenderer} when available, falling back to the
 * traditional {@link Screen#setLiveModal} path.
 */
public final class JLineApprovalPrompt implements UserApprovalPrompt {

    private static final List<ApprovalPanel.Action> ACTIONS = List.of(
            new ApprovalPanel.Action(ApprovalPanel.Decision.ALLOW_ONCE, "Allow once", false, "a"),
            new ApprovalPanel.Action(ApprovalPanel.Decision.ALLOW_SESSION, "Allow for session", false, "s"),
            new ApprovalPanel.Action(ApprovalPanel.Decision.DENY, "Deny", true, "d"));

    private static final String FOOTER = "←/→ select   Enter confirm   Esc deny";

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

        if (readerLock != null) readerLock.pause();
        screen.setCursorVisible(false);
        try {
            while (true) {
                TerminalKeys.KeyPress key;
                try {
                    key = TerminalKeys.readKey(terminal.reader());
                } catch (IOException e) {
                    tr.resolvePermission(toolUseId, true);
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
                        tr.resolvePermission(toolUseId, true);
                        fireInterrupt(CancellationToken.REASON_PERMISSION_DENIED);
                        return ApprovalResponse.DENY;
                    }
                    default -> {
                        int ch = Character.toLowerCase(key.ch());
                        if (ch == 'a') {
                            tr.resolvePermission(toolUseId);
                            return ApprovalResponse.ALLOW_ONCE;
                        }
                        if (ch == 's') {
                            tr.resolvePermission(toolUseId);
                            return ApprovalResponse.ALLOW_SESSION;
                        }
                        if (ch == 'd') {
                            tr.resolvePermission(toolUseId, true);
                            return ApprovalResponse.DENY;
                        }
                    }
                }
            }
        } finally {
            screen.setCursorVisible(true);
            if (readerLock != null) readerLock.resume();
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
        List<ChoicePrompt.Option<ApprovalPanel.Decision>> options = new ArrayList<>();
        for (ApprovalPanel.Action action : ACTIONS) {
            options.add(new ChoicePrompt.Option<>(
                    action.decision(),
                    action.label(),
                    action.destructive() ? "(destructive)" : "",
                    "",
                    action.hotkey()));
        }

        ChoicePrompt.Model<ApprovalPanel.Decision> model = new ChoicePrompt.Model<>(
                "Permission required",
                "",
                options,
                FOOTER,
                0,
                true);

        Consumer<String> permissionInterrupt =
                reason -> fireInterrupt(CancellationToken.REASON_PERMISSION_DENIED);
        if (readerLock != null) readerLock.pause();
        try {
            Optional<ApprovalPanel.Decision> choice =
                    new InlineChoicePrompt<ApprovalPanel.Decision>(
                            screen, terminal, readerLock, permissionInterrupt).choose(model);
            ApprovalPanel.Decision decision = choice.orElse(ApprovalPanel.Decision.DENY);
            return responseFor(decision);
        } catch (IOException e) {
            fireInterrupt(CancellationToken.REASON_PERMISSION_DENIED);
            return ApprovalResponse.DENY;
        } finally {
            if (readerLock != null) readerLock.resume();
        }
    }

    private void fireInterrupt(String reason) {
        if (onInterrupt != null) onInterrupt.accept(reason);
    }

    private static ApprovalResponse responseFor(ApprovalPanel.Decision decision) {
        return switch (decision) {
            case DENY -> ApprovalResponse.DENY;
            case ALLOW_ONCE -> ApprovalResponse.ALLOW_ONCE;
            case ALLOW_SESSION -> ApprovalResponse.ALLOW_SESSION;
        };
    }
}
