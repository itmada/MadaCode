package madacode.render.turn;

import madacode.render.MarkdownLayoutFrame;
import madacode.render.StreamingMarkdownDocument;
import madacode.tui.theme.Tk;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates streaming assistant text chunks behind a single Markdown stream.
 *
 * <p>{@link #drainCommittedLines(int)} exposes committed lines for scrollback
 * and {@link #render(int)} exposes the live preview from the same layout
 * snapshot, so there is no split between committed and preview rendering.
 */
public final class AssistantTextRenderable implements Renderable {

    private final StreamingMarkdownDocument markdown = new StreamingMarkdownDocument();
    private boolean finalized;
    private boolean marginIssued;
    private int cachedWidth = Integer.MIN_VALUE;
    private int lastWidth = Integer.MIN_VALUE;
    private boolean cachedFinalized;
    private MarkdownLayoutFrame cachedFrame = new MarkdownLayoutFrame(List.of(), List.of());
    private boolean committedDrainedForCache;
    private final List<String> undrainedPermanentLines = new ArrayList<>();

    public synchronized void append(String chunk) {
        markdown.append(chunk);
        invalidateCache();
    }

    public synchronized void finalizeText() {
        finalized = true;
        invalidateCache();
    }

    /** Drain committed (completed) lines for scrollback. Caller must batch these. */
    public synchronized List<String> drainCommittedLines() {
        return drainCommittedLines(lastWidth == Integer.MIN_VALUE ? Integer.MAX_VALUE : lastWidth);
    }

    /** Drain committed lines using the current terminal width for markdown layout. */
    public synchronized List<String> drainCommittedLines(int maxWidth) {
        MarkdownLayoutFrame frame = ensureLayout(maxWidth);
        if (committedDrainedForCache && undrainedPermanentLines.isEmpty()) {
            return List.of();
        }
        List<String> result = new ArrayList<>(
                undrainedPermanentLines.size() + frame.permanentLines().size());
        result.addAll(undrainedPermanentLines);
        undrainedPermanentLines.clear();
        if (!committedDrainedForCache) {
            result.addAll(frame.permanentLines());
        }
        committedDrainedForCache = true;
        return result.isEmpty() ? List.of() : List.copyOf(result);
    }

    @Override
    public synchronized List<String> render(int maxWidth) {
        MarkdownLayoutFrame frame = ensureLayout(maxWidth);
        List<String> result = frame.liveLines();
        if (result.isEmpty()) return List.of();
        if (!finalized) {
            int last = result.size() - 1;
            result = new java.util.ArrayList<>(result);
            result.set(last, result.get(last) + Tk.dim("▌"));
        }
        return result;
    }

    @Override
    public synchronized boolean isFinalized() {
        return finalized;
    }

    @Override
    public synchronized boolean isMarginIssued() { return marginIssued; }

    @Override
    public synchronized void markMarginIssued() { marginIssued = true; }

    private MarkdownLayoutFrame ensureLayout(int maxWidth) {
        lastWidth = maxWidth;
        if (cachedWidth == maxWidth && cachedFinalized == finalized) {
            return cachedFrame;
        }
        preserveUndrainedPermanentLines();
        cachedFrame = markdown.layout(maxWidth, finalized);
        cachedWidth = maxWidth;
        cachedFinalized = finalized;
        committedDrainedForCache = false;
        return cachedFrame;
    }

    private void invalidateCache() {
        preserveUndrainedPermanentLines();
        cachedWidth = Integer.MIN_VALUE;
        cachedFinalized = false;
        cachedFrame = new MarkdownLayoutFrame(List.of(), List.of());
        committedDrainedForCache = false;
    }

    private void preserveUndrainedPermanentLines() {
        if (!committedDrainedForCache && !cachedFrame.permanentLines().isEmpty()) {
            undrainedPermanentLines.addAll(cachedFrame.permanentLines());
            committedDrainedForCache = true;
        }
    }
}
