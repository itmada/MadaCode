package madacode.render;

public final class ThinkingVerbs {

    private static final String[] VERBS = {
            "Pondering", "Thinking", "Cogitating", "Distilling", "Brewing",
            "Synthesizing", "Mulling", "Reflecting", "Considering", "Analyzing",
            "Computing", "Reasoning", "Crystallizing", "Processing",
            "Contemplating", "Deliberating", "Musing", "Weighing",
            "Pondering", "Conjuring", "Untangling", "Unraveling",
            "Examining", "Investigating", "Exploring"
    };

    private ThinkingVerbs() {}

    public static String pick(long elapsedMs) {
        long safeElapsed = Math.max(0L, elapsedMs);
        int idx = (int) ((safeElapsed / 7000L) % VERBS.length);
        return VERBS[idx];
    }
}
