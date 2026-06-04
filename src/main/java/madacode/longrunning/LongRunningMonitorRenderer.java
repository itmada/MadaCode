package madacode.longrunning;

import java.util.ArrayList;
import java.util.List;

public final class LongRunningMonitorRenderer {

    private static final String RULE = "────────────────────────────────────────────────────────";

    public List<String> render(LongRunningMonitorSnapshot snapshot) {
        List<String> lines = new ArrayList<>();
        lines.add(RULE);
        lines.add("Long-running: " + snapshot.stage());
        lines.add(taskLine(snapshot));
        lines.add("");
        lines.add("Now");
        lines.add(snapshot.currentTarget() == null ? "Selecting target..." : snapshot.currentTarget());
        lines.add(snapshot.currentAction() == null
                ? (snapshot.interrupting() ? "Stopping current worker safely..." : "Working...")
                : snapshot.currentAction());
        lines.add("");
        lines.add("Events");
        if (snapshot.recentEvents().isEmpty()) {
            lines.add("Waiting for worker events...");
        } else {
            lines.addAll(snapshot.recentEvents());
        }
        lines.add("");
        lines.add(snapshot.interrupting()
                ? "Interrupt requested; stopping current worker..."
                : "Esc / Ctrl+C interrupt");
        lines.add(RULE);
        return lines;
    }

    private static String taskLine(LongRunningMonitorSnapshot snapshot) {
        String worker = snapshot.workerSessionId() == null ? "Worker starting..." : "Worker " + snapshot.workerSessionId();
        String cycle = snapshot.cycle() == null
                ? "Cycle ?/?"
                : "Cycle " + snapshot.cycle() + "/" + (snapshot.limit() == null ? "?" : snapshot.limit());
        return "Task " + snapshot.taskId() + " · " + worker + " · " + cycle;
    }
}
