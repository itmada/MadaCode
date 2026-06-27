package madacode.render.turn;

import madacode.tui.Screen;
import madacode.tui.theme.Tk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the tree of {@link Renderable} items for the current turn.
 *
 * <p>Rendering uses a two-phase architecture:
 * <ol>
 *   <li>{@link #layout(int)} — pure function that walks items and produces
 *       a sequence of {@link OutputEntry} records.</li>
 *   <li>{@link #apply(List)} — splits entries into scrollback vs live,
 *       performs I/O, removes consumed items, updates per-item state.</li>
 * </ol>
 *
 * <p>This separation ensures spacing (leading margins) is decided once in
 * layout, and the two physical channels (scrollback, live) are pure
 * routing concerns in apply.  No shared mutable state is needed to
 * coordinate spacing between channels.
 */
public final class TurnView {

    private static final int MIN_LIVE_LINES = 3;
    private static final int LIVE_HEIGHT_RESERVED_LINES = 2;

    private final Screen screen;
    private final ScheduledExecutorService paintScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "turn-view-paint");
                t.setDaemon(true);
                return t;
            });

    private final List<Renderable> items = new ArrayList<>();
    private Renderable bottomPinned;
    private ScheduledFuture<?> pendingPaint;
    private final AtomicBoolean paintScheduled = new AtomicBoolean(false);
    private volatile boolean ended;
    private boolean scrollbackWritten;

    public TurnView(Screen screen) {
        this.screen = Objects.requireNonNull(screen, "screen");
    }

    // ---- mutation API -----------------------------------------------------

    public synchronized void add(Renderable r) {
        items.add(r);
        markDirty();
    }

    /**
     * Pin a renderable to the bottom of the live region, below all items. It
     * never spills to scrollback through the item flow and never pins items
     * above it (it is outside the ordering invariant). Used by the live plan
     * panel; the caller owns its finalize/summary spill on turn end.
     */
    public synchronized void setBottomPinned(Renderable r) {
        bottomPinned = r;
        markDirty();
    }

    public synchronized void clearBottomPinned() {
        bottomPinned = null;
        markDirty();
    }

    public synchronized void markDirty() {
        if (ended) return;
        if (paintScheduled.compareAndSet(false, true)) {
            try {
                pendingPaint = paintScheduler.schedule(this::doPaint, 16, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // scheduler already shut down
            }
        }
    }

    /** Synchronously paint once — used to ensure inline prompts are visible before reading keys. */
    public synchronized void flushNow() {
        if (ended) return;
        cancelPendingPaint();
        int width = screen.width();
        apply(layout(width));
    }

    /**
     * End the turn: force all remaining content to scrollback in one shot,
     * then clear all items so the next turn starts with a clean slate.
     */
    public synchronized void endTurn() {
        cancelPendingPaint();
        int width = screen.width();
        List<OutputEntry> entries = layoutForceAllPermanent(width);
        boolean hadVisibleScrollback = scrollbackWritten || !entries.isEmpty();
        apply(entries, false);
        if (hadVisibleScrollback) {
            // The turn is one logical block even when streaming has already
            // committed stable prefixes to scrollback. Only the final commit
            // owns the bottom boundary.
            screen.ensureScrollbackBoundary();
        }
        items.clear();
        bottomPinned = null;
        scrollbackWritten = false;
    }

    // ---- query API --------------------------------------------------------

    @SuppressWarnings("unchecked")
    public synchronized <T extends Renderable> T findByToolUseId(String id, Class<T> type) {
        for (Renderable r : items) {
            if (r instanceof ToolCardRenderable tc && id.equals(tc.toolUseId()) && type.isInstance(tc)) {
                return (T) tc;
            }
        }
        return null;
    }

    public synchronized void remove(Renderable r) {
        items.remove(r);
    }

    /** Package-private access for tests. */
    synchronized List<Renderable> items() {
        return items;
    }

    public synchronized void shutdown() {
        ended = true;
        endTurn();
        paintScheduler.shutdownNow();
    }

    // ---- paint ------------------------------------------------------------

    private synchronized void doPaint() {
        paintScheduled.set(false);
        if (ended) return;
        int width = screen.width();
        apply(layout(width));
    }

    // ---- layout / apply ---------------------------------------------------

    /**
     * Layout pass. Walks items in order, produces a sequence of
     * OutputEntry. Permanent entries (committed text lines, finalized
     * tool cards) are eligible for scrollback. Transient entries
     * (streaming partials, running cards) must stay in live.
     *
     * <p>Maintains the ordering invariant: once an entry is non-permanent,
     * all subsequent entries are non-permanent. This preserves the rule
     * "scrollback order = items order".
     *
     * <p><strong>Side effect:</strong> calls {@code drainCommittedLines()}
     * on text items that are in the permanent prefix. This is safe because
     * layout + apply are always called within the same synchronized block.
     */
    private List<OutputEntry> layout(int width) {
        List<OutputEntry> entries = new ArrayList<>();
        boolean stillInPrefix = true;

        for (Renderable item : items) {
            if (item instanceof AssistantTextRenderable atr) {
                if (stillInPrefix) {
                    List<String> committed = atr.drainCommittedLines(width);
                    if (!committed.isEmpty()) {
                        entries.add(new OutputEntry(
                                atr, committed,
                                shouldIssueLeadingMargin(entries, atr),
                                true));
                    }
                }
                List<String> partial = atr.render(width);
                if (!partial.isEmpty()) {
                    boolean permanent = atr.isFinalized() && stillInPrefix;
                    entries.add(new OutputEntry(
                            atr, partial,
                            shouldIssueLeadingMargin(entries, atr),
                            permanent));
                    if (!permanent) stillInPrefix = false;
                } else if (!atr.isFinalized()) {
                    stillInPrefix = false;
                }
            } else {
                List<String> rendered = item.render(width);
                if (rendered.isEmpty()) {
                    if (!item.isFinalized()) stillInPrefix = false;
                    continue;
                }
                boolean permanent = item.isFinalized() && stillInPrefix;
                entries.add(new OutputEntry(
                        item, rendered,
                        shouldIssueLeadingMargin(entries, item),
                        permanent));
                if (!permanent) stillInPrefix = false;
            }
        }

        return entries;
    }

    /**
     * Variant of layout() used by endTurn: ignores finalized state, treats
     * everything as permanent so the entire remaining content spills to
     * scrollback in one shot.
     */
    private List<OutputEntry> layoutForceAllPermanent(int width) {
        List<OutputEntry> entries = new ArrayList<>();

        for (Renderable item : items) {
            if (item instanceof AssistantTextRenderable atr) {
                List<String> committed = atr.drainCommittedLines(width);
                if (!committed.isEmpty()) {
                    entries.add(new OutputEntry(
                            atr, committed,
                            shouldIssueLeadingMargin(entries, atr),
                            true));
                }
                List<String> partial = atr.render(width);
                if (!partial.isEmpty()) {
                    entries.add(new OutputEntry(
                            atr, partial,
                            shouldIssueLeadingMargin(entries, atr),
                            true));
                }
            } else {
                List<String> rendered = item.render(width);
                if (rendered.isEmpty()) continue;
                entries.add(new OutputEntry(
                        item, rendered,
                        shouldIssueLeadingMargin(entries, item),
                        true));
            }
        }

        return entries;
    }

    /**
     * Applies a layout result. Splits entries at the permanent/transient
     * boundary, writes scrollback + live atomically, removes consumed
     * items, and updates per-item marginIssued state.
     */
    private void apply(List<OutputEntry> entries) {
        apply(entries, true);
    }

    private void apply(List<OutputEntry> entries, boolean includePinned) {
        // 1. Find split: last index where permanent == true
        int splitIndex = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).permanent()) splitIndex = i;
            else break;
        }

        // 2. Flatten to physical line lists, expanding leading margins
        List<String> scrollbackLines = new ArrayList<>();
        List<String> liveLines = new ArrayList<>();
        Set<Renderable> consumedItems = Collections.newSetFromMap(new IdentityHashMap<>());

        for (int i = 0; i < entries.size(); i++) {
            OutputEntry e = entries.get(i);
            List<String> target = (i <= splitIndex) ? scrollbackLines : liveLines;
            if (e.hasLeadingMargin()) target.add("");
            target.addAll(e.lines());
            if (i <= splitIndex) {
                if (e.hasLeadingMargin()) e.source().markMarginIssued();
                consumedItems.add(e.source());
            }
        }

        // 3. Remove items whose entries are ALL in scrollback AND the item is finalized.
        //    Unfinalized items (streaming text) must stay even if their committed lines
        //    were all drained in this pass — more data will arrive.
        final int finalSplit = splitIndex;
        final List<OutputEntry> finalEntries = entries;
        items.removeIf(item ->
                item.isFinalized()
                && consumedItems.contains(item)
                && allEntriesInScrollback(item, finalEntries, finalSplit));

        // 3.5 Append the bottom-pinned panel below all live items. It is not
        //     part of the item flow, so it never spills here and never pins
        //     items above it; the turn-end summary is the caller's job.
        if (includePinned && bottomPinned != null) {
            List<String> pinned = bottomPinned.render(screen.width());
            if (!pinned.isEmpty()) {
                if (!liveLines.isEmpty()) liveLines.add("");
                liveLines.addAll(pinned);
            }
        }

        liveLines = fitLiveViewport(liveLines);

        // 4. Atomic I/O
        if (!scrollbackLines.isEmpty()) {
            scrollbackWritten = true;
            screen.commitScrollbackAndSetStatus(scrollbackLines, liveLines);
        } else {
            screen.setLiveStatus(liveLines);
        }
    }

    private List<String> fitLiveViewport(List<String> lines) {
        if (lines.isEmpty()) {
            return lines;
        }
        int maxLines = liveViewportBudget();
        if (lines.size() <= maxLines) {
            return lines;
        }

        int hidden = lines.size() - maxLines + 1;
        List<String> visible = new ArrayList<>(maxLines);
        visible.add(Tk.dim("… " + hidden + " earlier live line" + (hidden == 1 ? "" : "s") + " hidden"));
        visible.addAll(lines.subList(lines.size() - maxLines + 1, lines.size()));
        return visible;
    }

    private int liveViewportBudget() {
        int height = Math.max(5, screen.height());
        return Math.max(MIN_LIVE_LINES, height - LIVE_HEIGHT_RESERVED_LINES);
    }

    /** True if every entry from this item is at or below splitIndex (i.e., in scrollback). */
    private static boolean allEntriesInScrollback(Renderable item, List<OutputEntry> entries, int splitIndex) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).source() == item && i > splitIndex) return false;
        }
        return true;
    }

    /** Returns true if no prior entry has the same source. */
    private static boolean firstEntryOf(List<OutputEntry> entries, Renderable item) {
        for (OutputEntry e : entries) {
            if (e.source() == item) return false;
        }
        return true;
    }

    /**
     * Leading margins separate items inside a turn. The first item in a turn
     * does not get one because the preceding user/input block already owns its
     * bottom boundary.
     */
    private boolean shouldIssueLeadingMargin(List<OutputEntry> entries, Renderable item) {
        return !item.isMarginIssued()
                && firstEntryOf(entries, item)
                && (!entries.isEmpty() || scrollbackWritten);
    }

    private synchronized void cancelPendingPaint() {
        if (pendingPaint != null) {
            pendingPaint.cancel(false);
            pendingPaint = null;
        }
        paintScheduled.set(false);
    }
}
