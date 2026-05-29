package madacode.render.turn;

import java.util.List;
import java.util.Objects;

/**
 * Unit of layout output. Produced by {@link TurnView#layout},
 * consumed by {@link TurnView#apply}. Carries enough metadata for
 * apply() to decide which physical channel (scrollback / live) the
 * entry belongs to, and to update per-item state.
 *
 * @param source          the Renderable that produced this entry
 * @param lines           the actual content lines (without leading margin)
 * @param hasLeadingMargin whether a "" should be emitted before {@code lines}
 * @param permanent       if true, can be split into scrollback; if false,
 *                        must stay in live area
 */
public record OutputEntry(
        Renderable source,
        List<String> lines,
        boolean hasLeadingMargin,
        boolean permanent) {

    public OutputEntry {
        Objects.requireNonNull(source, "source");
        lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
    }
}
