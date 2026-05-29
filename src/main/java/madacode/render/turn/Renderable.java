package madacode.render.turn;

import java.util.List;

/**
 * A renderable piece of the current turn's live view.
 *
 * <p>Pull model: callers invoke {@link #render(int)} to produce a list of
 * ANSI-styled lines at the given terminal width.  The result is a pure
 * function of the renderable's current state.
 */
public interface Renderable {

    /** Produce ANSI-styled lines for display, respecting {@code maxWidth}. */
    List<String> render(int maxWidth);

    /** True when this item will never change again — safe to spill to scrollback. */
    boolean isFinalized();

    /** Whether this item's leading-margin "" has already been written to scrollback. */
    default boolean isMarginIssued() { return false; }

    /** Called by TurnView.apply() after the margin entry crosses into scrollback. */
    default void markMarginIssued() {}
}
