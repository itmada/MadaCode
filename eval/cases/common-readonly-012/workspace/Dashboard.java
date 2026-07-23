/** Renders the current average. */
public final class Dashboard {

    private final MetricsWindow window;

    public Dashboard(MetricsWindow window) {
        this.window = window;
    }

    public String render() {
        return "avg=" + window.average();
    }
}
