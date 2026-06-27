package madacode.longrunning;

import madacode.tui.theme.Tk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders the live long-running monitor panel. A header bar with a status
 * badge, an identity row, cycle / feature progress bars, the current focus
 * card, a timestamped activity stream, and a key-hint footer. All styling
 * funnels through {@link Tk}; the layout targets a fixed {@link #WIDTH} column
 * budget so progress bars and the right-aligned badge line up.
 */
public final class LongRunningMonitorRenderer {

    private static final int WIDTH = 60;
    private static final int BAR_CELLS = 20;
    private static final String[] SPINNER = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    /**
     * Spinner advances one frame per {@link #render} call rather than off the
     * wall clock, so its frame rate equals the redraw rate — smooth instead of
     * stuttering when the two don't divide evenly.
     */
    private int spinnerTick;

    public List<String> render(LongRunningMonitorSnapshot snapshot) {
        List<String> lines = new ArrayList<>();
        lines.add(""); // breathing room from the log line above

        // Header: ▌ title .......................... badge
        String title = snapshot.title() == null ? "Long-running task" : snapshot.title();
        String left = Tk.accent("▌") + " " + Tk.bold(fit(title, WIDTH - 14));
        lines.add(rightAlign(left, badge(snapshot)));
        lines.add("");

        // Identity row: task · worker · model · elapsed
        lines.add(identityLine(snapshot));
        lines.add("");

        // Progress bars
        lines.add(progressLine("Cycle", cycleRatio(snapshot), true, cycleValue(snapshot)));
        if (snapshot.featuresTotal() > 0) {
            lines.add(progressLine("Features", featureRatio(snapshot), false, featureValue(snapshot)));
        }
        lines.add("");

        // Now: current focus, then the live action left-aligned beneath it.
        lines.add(nowTitleLine(snapshot));
        lines.add("");
        lines.add(nowDetailLine(snapshot, nextSpinnerFrame()));
        lines.add("");

        // Activity stream — drop the entry the Now card already headlines.
        lines.add(sectionRule("Activity"));
        List<LongRunningMonitorActivity> events = activityEvents(snapshot);
        if (events.isEmpty()) {
            lines.add(Tk.dim("   Waiting for worker events..."));
        } else {
            for (LongRunningMonitorActivity event : events) {
                lines.add(eventLine(event));
            }
        }
        lines.add("");

        // Footer
        lines.add(footerLine(snapshot));
        return lines;
    }

    // ---- header ---------------------------------------------------------

    private static String badge(LongRunningMonitorSnapshot snapshot) {
        if (snapshot.interrupting()) {
            return Tk.info("‖ INTERRUPT");
        }
        return switch (snapshot.stage() == null ? "" : snapshot.stage()) {
            case "COMPLETED" -> Tk.success("✓ COMPLETED");
            case "CANCELLED" -> Tk.warn("× CANCELLED");
            case "FAILED" -> Tk.failure("✗ FAILED");
            case "INTERRUPT" -> Tk.info("‖ INTERRUPT");
            default -> Tk.accent("◍ RUNNING");
        };
    }

    private static String identityLine(LongRunningMonitorSnapshot snapshot) {
        StringBuilder b = new StringBuilder("  ");
        b.append(Tk.dim(snapshot.taskId()));
        if (snapshot.workerSessionId() != null) {
            b.append(Tk.dim(" · worker ")).append(Tk.filePath(snapshot.workerSessionId()));
        } else {
            b.append(Tk.dim(" · worker starting…"));
        }
        if (snapshot.model() != null) {
            b.append(Tk.dim(" · ")).append(Tk.toolArg(snapshot.model()));
        }
        if (snapshot.elapsedSeconds() > 0) {
            b.append(Tk.dim(" · elapsed ")).append(clock(snapshot.elapsedSeconds()));
        }
        return b.toString();
    }

    // ---- progress bars --------------------------------------------------

    private static String progressLine(String label, double ratio, boolean accent, String value) {
        String bar = bar(ratio, accent);
        String padLabel = Tk.dim(String.format(Locale.ROOT, "  %-9s", label));
        return padLabel + bar + "  " + value;
    }

    private static String bar(double ratio, boolean accent) {
        double clamped = Math.max(0, Math.min(1, ratio));
        int filled = (int) Math.round(clamped * BAR_CELLS);
        if (clamped > 0 && filled == 0) {
            filled = 1;
        }
        int empty = BAR_CELLS - filled;
        String fill = "━".repeat(Math.max(0, filled - 1)) + (filled > 0 ? "╸" : "");
        String track = "─".repeat(Math.max(0, empty));
        String coloredFill = accent ? Tk.accent(fill) : Tk.success(fill);
        return coloredFill + dimTrack(track);
    }

    private static String dimTrack(String track) {
        return track.isEmpty() ? "" : Tk.dim(track);
    }

    private static double cycleRatio(LongRunningMonitorSnapshot snapshot) {
        if (snapshot.cycle() == null || snapshot.limit() == null || snapshot.limit() <= 0) {
            return 0;
        }
        return (double) snapshot.cycle() / snapshot.limit();
    }

    private static String cycleValue(LongRunningMonitorSnapshot snapshot) {
        if (snapshot.cycle() == null) {
            return Tk.dim("?/?");
        }
        String limit = snapshot.limit() == null ? "?" : String.valueOf(snapshot.limit());
        return snapshot.cycle() + Tk.dim("/" + limit);
    }

    private static double featureRatio(LongRunningMonitorSnapshot snapshot) {
        if (snapshot.featuresTotal() <= 0) {
            return 0;
        }
        return (double) snapshot.featuresPassing() / snapshot.featuresTotal();
    }

    private static String featureValue(LongRunningMonitorSnapshot snapshot) {
        String value = Tk.success(String.valueOf(snapshot.featuresPassing()))
                + Tk.dim("/" + snapshot.featuresTotal() + " passing");
        if (snapshot.issuesBlocked() > 0) {
            value += Tk.dim(" · ") + Tk.warn(snapshot.issuesBlocked() + " blocked");
        }
        return value;
    }

    // ---- now focus ------------------------------------------------------

    // Title row: ◆ Now <target>
    private static String nowTitleLine(LongRunningMonitorSnapshot snapshot) {
        String target = snapshot.currentTarget();
        return Tk.accent("  ◆ Now") + "   "
                + (target == null ? Tk.dim("Selecting target…") : styledTarget(target));
    }

    // Detail row: <pulse> live action ................... <ago>, aligned to ◆.
    private static String nowDetailLine(LongRunningMonitorSnapshot snapshot, String spin) {
        if (snapshot.interrupting()) {
            return "  " + Tk.failure("✗ Stopping current worker safely…");
        }
        String action = snapshot.currentAction() == null ? "Working…" : snapshot.currentAction();
        LongRunningMonitorActivityStatus status = snapshot.currentActionStatus();
        boolean failed = status == LongRunningMonitorActivityStatus.FAILURE;
        String glyph = nowGlyph(status, spin);
        String prefix = "  " + glyph + " ";
        String ago = agoShort(snapshot.secondsSinceLastEvent());
        if (ago == null) {
            String text = fit(action, Math.max(1, WIDTH - Tk.displayWidth(prefix)));
            return prefix + (failed ? Tk.failure(text) : text);
        }
        String label = ago + " ago";
        String agoStyled = snapshot.secondsSinceLastEvent() >= 300 ? Tk.warn(label) : Tk.dim(label);
        int textWidth = Math.max(1, WIDTH - Tk.displayWidth(prefix) - Tk.displayWidth(agoStyled) - 1);
        String text = fit(action, textWidth);
        String left = prefix + (failed ? Tk.failure(text) : text);
        return rightAlign(left, agoStyled);
    }

    private static String styledTarget(String target) {
        int sep = target.indexOf(' ');
        if (sep > 0 && sep <= 8) {
            return Tk.filePath(target.substring(0, sep)) + Tk.dim(" · ") + target.substring(sep + 1);
        }
        return target;
    }

    /**
     * Recent events with the one the Now card already headlines removed, so the
     * live focus line and the log don't show the same text back to back. Falls
     * back to the full list if every entry matches (keeps the log non-empty).
     */
    private static List<LongRunningMonitorActivity> activityEvents(LongRunningMonitorSnapshot snapshot) {
        List<LongRunningMonitorActivity> events = snapshot.recentEvents();
        String current = snapshot.currentAction();
        if (current == null || events.isEmpty()) {
            return events;
        }
        List<LongRunningMonitorActivity> filtered = new ArrayList<>(events.size());
        for (LongRunningMonitorActivity event : events) {
            if (!current.equals(event.body())) {
                filtered.add(event);
            }
        }
        return filtered.isEmpty() ? events : filtered;
    }

    private static String agoShort(long seconds) {
        if (seconds < 2) {
            return null;
        }
        if (seconds < 60) {
            return seconds + "s";
        }
        return (seconds / 60) + "m";
    }

    // ---- activity stream ------------------------------------------------

    private static String sectionRule(String label) {
        String head = Tk.dim("  ── ") + Tk.dim(label) + " ";
        int used = 2 + 3 + label.length() + 1; // "  ── label "
        int dashes = Math.max(1, WIDTH - used);
        return head + Tk.dim("─".repeat(dashes));
    }

    static String eventLine(LongRunningMonitorActivity event) {
        if (event == null || event.body().isBlank()) {
            return "";
        }
        String glyph = eventGlyph(event);
        StringBuilder b = new StringBuilder("   ");
        if (event.time() != null) {
            b.append(Tk.dim(event.time())).append("  ");
        }
        b.append(glyph).append(' ');
        String body = event.repeatCount() > 1 ? event.body() + " ×" + event.repeatCount() : event.body();
        int textWidth = Math.max(1, WIDTH - Tk.displayWidth(b.toString()));
        b.append(Tk.dim(fit(body, textWidth)));
        return b.toString();
    }

    private static String eventGlyph(LongRunningMonitorActivity event) {
        return switch (event.status()) {
            case FAILURE -> Tk.failure("✗");
            case WARNING -> Tk.warn("!");
            case INFO -> Tk.info("?");
            case SUCCESS -> successGlyph(event.kind());
            case RUNNING, NEUTRAL -> runningGlyph(event.kind());
        };
    }

    private static String nowGlyph(LongRunningMonitorActivityStatus status, String spin) {
        return switch (status == null ? LongRunningMonitorActivityStatus.RUNNING : status) {
            case FAILURE -> Tk.failure("✗");
            case WARNING -> Tk.warn("!");
            case INFO -> Tk.info("?");
            case SUCCESS -> Tk.success("✓");
            case RUNNING, NEUTRAL -> Tk.thinking(spin);
        };
    }

    private static String successGlyph(LongRunningMonitorActivityKind kind) {
        return switch (kind) {
            case FINISHED, REPORT -> Tk.success("✓");
            default -> Tk.dim("·");
        };
    }

    private static String runningGlyph(LongRunningMonitorActivityKind kind) {
        return switch (kind) {
            case CYCLE -> Tk.accent("●");
            case COMMAND, INSPECT, UPDATE -> Tk.filePath("›");
            default -> Tk.dim("·");
        };
    }

    // ---- footer ---------------------------------------------------------

    private static String footerLine(LongRunningMonitorSnapshot snapshot) {
        if (snapshot.interrupting()) {
            return Tk.dim("  Interrupt requested; stopping current worker…");
        }
        return Tk.dim("  ") + Tk.bold(Tk.dim("esc")) + Tk.dim(" stop   ")
                + Tk.bold(Tk.dim("ctrl+c")) + Tk.dim(" interrupt   ")
                + Tk.bold(Tk.dim("↑↓")) + Tk.dim(" scroll");
    }

    // ---- helpers --------------------------------------------------------

    private static String rightAlign(String left, String right) {
        int pad = WIDTH - Tk.displayWidth(left) - Tk.displayWidth(right);
        return left + " ".repeat(Math.max(1, pad)) + right;
    }

    private static String clock(long seconds) {
        long m = seconds / 60;
        long s = seconds % 60;
        if (m >= 60) {
            long h = m / 60;
            m = m % 60;
            return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s);
        }
        return String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    private String nextSpinnerFrame() {
        return SPINNER[Math.floorMod(spinnerTick++, SPINNER.length)];
    }

    private static String fit(String value, int maxWidth) {
        if (value == null) {
            return "";
        }
        if (Tk.displayWidth(value) <= maxWidth) {
            return value;
        }
        String dots = "…";
        StringBuilder out = new StringBuilder();
        int used = 0;
        int budget = Math.max(0, maxWidth - 1);
        for (int i = 0; i < value.length(); i++) {
            String ch = value.substring(i, i + 1);
            int w = Tk.displayWidth(ch);
            if (used + w > budget) {
                break;
            }
            out.append(ch);
            used += w;
        }
        return out + dots;
    }
}
