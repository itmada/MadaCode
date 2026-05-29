package madacode.render.turn;

import madacode.render.MarkdownRenderer;
import madacode.tui.theme.Tk;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates streaming assistant text chunks, splitting completed lines
 * (committed to scrollback) from the trailing partial line (kept live).
 *
 * <p>Each {@link #append(String)} call feeds the chunk to a persistent
 * {@link MarkdownRenderer}, extracting complete lines as they arrive.
 * {@link #drainCommittedLines()} hands those lines to the paint loop for
 * scrollback. {@link #render(int)} returns only the trailing partial for
 * the live area.
 */
public final class AssistantTextRenderable implements Renderable {

    private final MarkdownRenderer markdown = new MarkdownRenderer();
    private final List<String> rawCommittedLines = new ArrayList<>();
    private final StringBuilder rawBuffer = new StringBuilder();
    private boolean finalized;
    private boolean marginIssued;

    public synchronized void append(String chunk) {
        rawBuffer.append(chunk);
        int nl;
        while ((nl = rawBuffer.indexOf("\n")) >= 0) {
            String rawLine = rawBuffer.substring(0, nl);
            rawBuffer.delete(0, nl + 1);
            rawCommittedLines.add(rawLine);
        }
    }

    public synchronized void finalizeText() {
        if (rawBuffer.length() > 0) {
            String remaining = rawBuffer.toString();
            rawBuffer.setLength(0);
            rawCommittedLines.add(remaining);
        }
        finalized = true;
    }

    /** Drain committed (completed) lines for scrollback. Caller must batch these. */
    public synchronized List<String> drainCommittedLines() {
        return drainCommittedLines(Integer.MAX_VALUE);
    }

    /** Drain committed lines using the current terminal width for markdown layout. */
    public synchronized List<String> drainCommittedLines(int maxWidth) {
        List<String> result = new ArrayList<>();
        if (rawCommittedLines.isEmpty()) {
            drainRenderedLines(result, maxWidth);
            return result.isEmpty() ? List.of() : result;
        }
        for (String rawLine : rawCommittedLines) {
            markdown.append(rawLine + "\n");
        }
        rawCommittedLines.clear();
        drainRenderedLines(result, maxWidth);
        return result;
    }

    @Override
    public synchronized List<String> render(int maxWidth) {
        List<String> result = new ArrayList<>();

        result.addAll(markdown.previewBufferedTable(maxWidth));

        String partial = rawBuffer.toString();
        if (!partial.isEmpty()) {
            result.addAll(markdown.renderPartialLines(partial, maxWidth));
        }

        if (result.isEmpty()) return List.of();

        if (!finalized) {
            int last = result.size() - 1;
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

    private void drainRenderedLines(List<String> result, int maxWidth) {
        String rendered;
        while ((rendered = markdown.renderLine(maxWidth, !finalized)) != null) {
            result.add(rendered);
        }
        if (finalized) {
            while ((rendered = markdown.flushRemaining(maxWidth)) != null) {
                result.add(rendered);
            }
        }
    }
}
