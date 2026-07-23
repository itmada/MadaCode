/** Sliding window of samples for averaging. */
public final class MetricsWindow {

    private final int[] samples;
    private int count;

    public MetricsWindow(int capacity) {
        this.samples = new int[capacity];
    }

    public void add(int value) {
        samples[count % samples.length] = value;
        count++;
    }

    /** Average of the samples currently in the window. */
    public int average() {
        int size = Math.min(count, samples.length);
        int sum = 0;
        for (int i = 0; i < size; i++) {
            sum += samples[i];
        }
        return sum / size;
    }
}
