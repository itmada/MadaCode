package madacode.tui;

/**
 * A resource that can be temporarily suspended and resumed, used to
 * coordinate background threads (e.g. ESC-interrupt monitor) with
 * foreground input loops (e.g. permission approval pane).
 */
public interface Suspendable {
    void pause();
    void resume();
}
