package madacode.render;

import madacode.core.model.MetaEvent;
import madacode.core.session.SessionListener;
import madacode.tui.Screen;
import madacode.tui.widget.SessionContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static madacode.tui.theme.Tk.*;

/**
 * Renders ephemeral meta events (compact, plan-mode, token, error)
 * to scrollback and status bar.  Separated from {@link MessageStreamRenderer}
 * so that class stays focused on session message history.
 */
public final class MetaEventRenderer implements SessionListener {

    private final Screen screen;
    private final SessionContext sessionContext;

    public MetaEventRenderer(Screen screen) {
        this(screen, null);
    }

    public MetaEventRenderer(Screen screen, SessionContext sessionContext) {
        this.screen = Objects.requireNonNull(screen, "screen");
        this.sessionContext = sessionContext;
    }

    @Override
    public void onMetaEvent(MetaEvent meta) {
        switch (meta) {
            case MetaEvent.CompactStarted s ->
                    writeStage(StageWriter.Status.RUNNING,
                            "Compacting conversation",
                            List.of((s.estimatedTokens() / 1000) + "k → soft limit "
                                    + (s.softLimit() / 1000) + "k"),
                            List.of(),
                            false);
            case MetaEvent.CompactCompleted c -> {
                var r = c.result();
                writeStage(StageWriter.Status.SUCCESS,
                        "Compacted",
                        List.of((r.beforeTokens() / 1000) + "k → "
                                + (r.afterTokens() / 1000) + "k via " + r.strategyName(),
                                r.messagesCompacted() + " summarized · " + r.messagesKept() + " kept"),
                        List.of(),
                        false);
            }
            case MetaEvent.CompactFailed f ->
                    writeStage(StageWriter.Status.FAILED, "Compact failed", List.of(f.reason()), List.of(), false);
            case MetaEvent.PlanModeEntered i ->
                    writeStage(StageWriter.Status.INFO, "Plan mode", List.of("activated · host-controlled read-only mode"), List.of(), false);
            case MetaEvent.PlanModeExited e ->
                    writeStage(StageWriter.Status.INFO, "Plan mode", List.of("exited"), List.of(), false);
            case MetaEvent.PlanRejected r ->
                    writeStage(StageWriter.Status.WARN, "Plan mode", List.of("rejected · staying in plan mode"),
                            r.summary() != null && !r.summary().isBlank() ? List.of(r.summary()) : List.of(), false);
            case MetaEvent.PlanUpdated p -> {
                // TurnRenderer owns the live, in-place plan panel during the turn
                // and spills its one-line summary at turn end. Nothing to do here.
            }
            case MetaEvent.ModelRequestStarted s -> {
                // TurnRenderer owns the transient turn status row.
            }
            case MetaEvent.TokenReport u -> {
                if (sessionContext != null) {
                    var usage = u.usage();
                    sessionContext.setTokens(usage.inputTokens()
                            + usage.cacheReadTokens()
                            + usage.cacheCreationTokens()
                            + usage.outputTokens());
                }
            }
            case MetaEvent.SubAgentStarted ignored -> {
                // Sub-agent visual lifecycle is owned by the agent/skill tool card.
                // Keep this meta event for non-visual observers and compatibility.
            }
            // TurnRenderer owns error display during turns (via abortTurn).
            case MetaEvent.Error e -> { }
        }
    }

    public void reset() {
        // Kept for callers that reset renderers together; token context is
        // updated only by the next TokenReport.
    }

    private void writeStage(
            StageWriter.Status status,
            String title,
            List<String> summary,
            List<String> verbose,
            boolean hasMore) {
        List<String> lines = new ArrayList<>(
                StageWriter.render(new StageWriter.Stage(status, title, summary, verbose, hasMore)));
        BlockSpacing.scrollbackBlock(screen, lines);
    }
}
