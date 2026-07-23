/** Feeds samples into the window. */
public final class SampleFeed {

    private final MetricsWindow window;

    public SampleFeed(MetricsWindow window) {
        this.window = window;
    }

    public void push(int[] values) {
        for (int value : values) {
            window.add(value);
        }
    }
}
