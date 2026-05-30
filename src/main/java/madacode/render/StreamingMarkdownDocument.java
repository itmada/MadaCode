package madacode.render;

import java.util.ArrayList;
import java.util.List;

public final class StreamingMarkdownDocument {

    private final MarkdownRenderer renderer = new MarkdownRenderer();
    private final StringBuilder rawPending = new StringBuilder();

    public synchronized void append(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        rawPending.append(chunk);
    }

    public synchronized MarkdownLayoutFrame layout(int maxWidth, boolean finalized) {
        feedPending(finalized);

        List<String> permanentLines = new ArrayList<>();
        String rendered;
        while ((rendered = renderer.renderLine(maxWidth, !finalized)) != null) {
            permanentLines.add(rendered);
        }
        if (finalized) {
            while ((rendered = renderer.flushRemaining(maxWidth)) != null) {
                permanentLines.add(rendered);
            }
        }

        if (finalized) {
            return new MarkdownLayoutFrame(permanentLines, List.of());
        }

        List<String> liveLines = new ArrayList<>(renderer.previewBufferedTable(maxWidth));
        if (rawPending.length() > 0) {
            liveLines.addAll(renderer.renderPartialLines(rawPending.toString(), maxWidth));
        }
        liveLines = MarkdownSpacingPolicy.applyLeadingBlank(liveLines, renderer.hasPendingBlockSeparatorForPreview());
        return new MarkdownLayoutFrame(permanentLines, liveLines);
    }

    public synchronized void reset() {
        rawPending.setLength(0);
        renderer.reset();
    }

    private void feedPending(boolean finalized) {
        if (rawPending.isEmpty()) {
            return;
        }
        if (finalized) {
            renderer.append(rawPending.toString());
            rawPending.setLength(0);
            return;
        }

        int lastNewline = rawPending.lastIndexOf("\n");
        if (lastNewline < 0) {
            return;
        }

        renderer.append(rawPending.substring(0, lastNewline + 1));
        rawPending.delete(0, lastNewline + 1);
    }
}
