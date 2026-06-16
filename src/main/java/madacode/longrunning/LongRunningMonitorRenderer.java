package madacode.longrunning;

import madacode.tui.theme.Tk;

import java.util.ArrayList;
import java.util.List;

public final class LongRunningMonitorRenderer {

    private static final String RULE = "────────────────────────────────────────────────────────";

    public List<String> render(LongRunningMonitorSnapshot snapshot) {
        List<String> lines = new ArrayList<>();
        lines.add(Tk.dim(RULE));
        lines.add(Tk.dim("Long-running: ") + styledStage(snapshot.stage()));
        lines.add(Tk.dim(taskLine(snapshot)));
        lines.add("");
        lines.add(Tk.dim("Target"));
        lines.add(Tk.bold(snapshot.currentTarget() == null ? "Selecting target..." : snapshot.currentTarget()));
        lines.add("");
        lines.add(Tk.dim("Activity"));
        String activity = snapshot.currentAction() == null
                ? (snapshot.interrupting() ? "Stopping current worker safely..." : "Working...")
                : snapshot.currentAction();
        lines.add(snapshot.interrupting() ? Tk.failure(activity) : activity);
        String idle = idleLine(snapshot.secondsSinceLastEvent());
        if (idle != null) {
            lines.add(Tk.dim(idle));
        }
        lines.add("");
        lines.add(Tk.dim("Events"));
        if (snapshot.recentEvents().isEmpty()) {
            lines.add(Tk.dim("Waiting for worker events..."));
        } else {
            snapshot.recentEvents().stream()
                    .map(LongRunningMonitorRenderer::eventLine)
                    .forEach(lines::add);
        }
        lines.add("");
        lines.add(Tk.dim(snapshot.interrupting()
                ? "Interrupt requested; stopping current worker..."
                : "Esc / Ctrl+C interrupt"));
        lines.add(Tk.dim(RULE));
        return lines;
    }

    private static String styledStage(String stage) {
        return switch (stage == null ? "" : stage) {
            case "RUNNING" -> Tk.running("RUNNING");
            case "DONE" -> Tk.success("DONE");
            case "INTERRUPT" -> Tk.info("INTERRUPT");
            default -> Tk.dim(stage == null ? "RUNNING" : stage);
        };
    }

    private static String eventLine(String line) {
        if (line != null && line.length() > 6
                && Character.isDigit(line.charAt(0))
                && Character.isDigit(line.charAt(1))
                && line.charAt(2) == ':'
                && Character.isDigit(line.charAt(3))
                && Character.isDigit(line.charAt(4))
                && line.charAt(5) == ' ') {
            return Tk.dim(line.substring(0, 5)) + line.substring(5);
        }
        return line == null ? "" : line;
    }

    private static String taskLine(LongRunningMonitorSnapshot snapshot) {
        String worker = snapshot.workerSessionId() == null ? "Worker starting..." : "Worker " + snapshot.workerSessionId();
        String cycle = snapshot.cycle() == null
                ? "Cycle ?/?"
                : "Cycle " + snapshot.cycle() + "/" + (snapshot.limit() == null ? "?" : snapshot.limit());
        return "Task " + snapshot.taskId() + " · " + worker + " · " + cycle;
    }

    private static String idleLine(long secondsSinceLastEvent) {
        if (secondsSinceLastEvent < 60) {
            return null;
        }
        long minutes = secondsSinceLastEvent / 60;
        if (minutes < 5) {
            return "No worker event for " + minutes + "m.";
        }
        return "No worker event for " + minutes + "m; the current command or API call may be stalled.";
    }
}
