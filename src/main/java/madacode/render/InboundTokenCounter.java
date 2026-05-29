package madacode.render;

public final class InboundTokenCounter {

    private long chars;

    public synchronized void onTextChunk(String chunk) {
        if (chunk != null) {
            chars += chunk.length();
        }
    }

    public synchronized void reset() {
        chars = 0L;
    }

    /** Rough estimate: about 4 chars per token. */
    public synchronized long currentTokens() {
        return chars / 4L;
    }
}
