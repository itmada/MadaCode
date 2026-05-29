package madacode.render;

/**
 * Unicode spinner frames for progress indication.
 */
public final class Spinner {

    private static final String[] DOTS = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final String[] THINKING = {"✻", "✶", "✧", "✦", "✶"};

    private final String[] frames;
    private int index;

    /** Create a spinner with the given frames. */
    public Spinner(String... frames) {
        if (frames == null || frames.length == 0) {
            throw new IllegalArgumentException("frames must not be empty");
        }
        this.frames = frames.clone();
    }

    /** Pre-built dot spinner ("⠋⠙⠹…"). */
    public static Spinner dots() {
        return new Spinner(DOTS);
    }

    /** Pre-built thinking spinner ("✻✶✧✦✶"). */
    public static Spinner thinking() {
        return new Spinner(THINKING);
    }

    /** Returns the next frame character. */
    public String tick() {
        String frame = frames[index];
        index = (index + 1) % frames.length;
        return frame;
    }

    /** Current frame without advancing. */
    public String current() {
        return frames[index];
    }

    /** Reset to first frame. */
    public void reset() {
        index = 0;
    }
}
