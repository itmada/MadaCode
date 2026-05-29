package madacode.cli;

import madacode.events.AppEvents;
import madacode.events.DiagnosticEvent;
import madacode.events.EventContext;

import madacode.tui.Suspendable;
import madacode.tui.TerminalKeys;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Centralized interrupt controller that unifies ESC keystroke monitoring
 * and SIGINT handling into a single entry point.
 *
 * <p>Lifecycle:
 * <pre>{@code
 *   controller.beginTurn(turnId, handle.cancel());
 *   try {
 *       ...
 *   } finally {
 *       controller.endTurn();
 *   }
 * }</pre>
 *
 * <p>All interrupt sources (ESC, Ctrl+C) funnel through the onCancel
 * callback registered at beginTurn time.
 *
 * <p>Implements {@link Suspendable} so exclusive foreground panes (e.g. the
 * permission approval prompt) can temporarily pause ESC monitoring to avoid
 * two readers racing on the same terminal input.
 */
public final class InterruptController implements Suspendable {

    private final Terminal terminal;
    private final SignalCancellationBridge signalBridge = new SignalCancellationBridge();

    private volatile String activeTurnId;
    private volatile Consumer<String> onCancel;
    private SignalCancellationBridge.Registration sigintReg;
    private final AtomicBoolean done = new AtomicBoolean();
    private final AtomicBoolean paused = new AtomicBoolean();
    private final Object readLock = new Object();
    private Thread escThread;
    private Attributes previousAttributes;

    public InterruptController(Terminal terminal) {
        this.terminal = terminal;
    }

    /**
     * Begin a user-facing turn. ESC monitoring and SIGINT handling are
     * activated. Call {@link #endTurn()} in a {@code finally} block.
     */
    public synchronized void beginTurn(String turnId, Consumer<String> onCancel) {
        this.activeTurnId = turnId;
        this.onCancel = onCancel;
        // --- SIGINT (Ctrl+C) ---
        this.sigintReg = signalBridge.activate(() -> interrupt("sigint"));
        // --- ESC keystroke ---
        previousAttributes = terminal.enterRawMode();
        startEscMonitor();
    }

    /**
     * End the current turn, stopping ESC monitoring and restoring the
     * previous SIGINT handler. Idempotent.
     */
    public synchronized void endTurn() {
        done.set(true);
        if (escThread != null) {
            try {
                escThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (escThread.isAlive()) {
                AppEvents.publisher().publish(DiagnosticEvent.warn(
                        EventContext.bootstrap("InterruptController"),
                        "ESC monitor thread did not exit cleanly, forcing terminal restore"));
                restoreAttributes();
            }
            escThread = null;
        }
        restoreAttributes();
        done.set(false);
        paused.set(false);
        if (sigintReg != null) {
            sigintReg.close();
            sigintReg = null;
        }
        this.activeTurnId = null;
        this.onCancel = null;
    }

    /**
     * Cancel the active turn. Safe to call from any thread.
     */
    public synchronized boolean interrupt(String reason) {
        Consumer<String> cb = this.onCancel;
        if (cb == null) {
            return false;
        }
        cb.accept(reason);
        return true;
    }

    public synchronized boolean hasActiveTurn() {
        return onCancel != null;
    }

    // ---- Suspendable -------------------------------------------------------

    @Override
    public void pause() {
        paused.set(true);
        synchronized (readLock) { }
    }

    @Override
    public void resume() {
        paused.set(false);
    }

    // ---- internal ----------------------------------------------------------

    private void startEscMonitor() {
        escThread = new Thread(this::watchEsc, "mada-esc-interrupt");
        escThread.setDaemon(true);
        escThread.start();
    }

    private void watchEsc() {
        try {
            NonBlockingReader reader = terminal.reader();
            while (!done.get()) {
                if (paused.get()) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                java.util.Optional<TerminalKeys.KeyPress> kp;
                synchronized (readLock) {
                    if (paused.get()) continue;
                    kp = TerminalKeys.pollKey(reader, 100);
                }
                if (kp.isEmpty()) continue;
                if (kp.get().key() == TerminalKeys.Key.ESCAPE) {
                    interrupt("esc");
                    return;
                }
            }
        } catch (IOException ignored) {
        }
    }

    private void restoreAttributes() {
        if (previousAttributes != null) {
            terminal.setAttributes(previousAttributes);
            previousAttributes = null;
        }
    }
}
