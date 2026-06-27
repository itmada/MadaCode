package madacode.tui;


import java.util.ArrayList;
import java.util.List;

/**
 * Minimal terminal abstraction for inline scrollback rendering.
 *
 * <p>No bottom-pinned regions, no DECSTBM scroll regions, no focus stack.
 * All output flows through the normal scrollback — typing scrolls
 * naturally like a standard CLI.
 */
public interface Screen {

    /** Append one line to scrollback. */
    default void scrollback(String line) {
        scrollback(List.of(line));
    }

    /** Append a batch of lines to scrollback. */
    void scrollback(List<String> lines);

    /**
     * Commit one complete user-visible block to scrollback. The block owns its
     * bottom boundary; callers should not add a leading blank before the next
     * block.
     */
    default void commitBlock(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        scrollback(lines);
        if (!lines.getLast().isEmpty()) {
            ensureScrollbackBoundary();
        }
    }

    /** Commit one complete user-visible block to scrollback. */
    default void commitBlock(String line) {
        commitBlock(List.of(line));
    }

    /**
     * Ensure the next prompt or content block starts below a blank scrollback
     * line. Implementations may suppress duplicate blanks.
     */
    default void ensureScrollbackBoundary() {
        scrollback("");
    }

    /**
     * Thread-safe user-visible notification path for async events.
     *
     * <p>Defaults to normal scrollback so existing Screen implementations keep
     * working without special handling.
     */
    default void notifyAsync(String line) {
        notifyAsync(List.of(line));
    }

    /**
     * Thread-safe user-visible notification path for async events.
     *
     * <p>Defaults to normal scrollback so existing Screen implementations keep
     * working without special handling.
     */
    default void notifyAsync(List<String> lines) {
        scrollback(lines);
    }

    /** Thread-safe async variant of {@link #commitBlock(List)}. */
    default void commitAsyncBlock(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        notifyAsync(withTrailingBoundary(lines));
    }

    /** Thread-safe async variant of {@link #commitBlock(String)}. */
    default void commitAsyncBlock(String line) {
        commitAsyncBlock(List.of(line));
    }

    private static List<String> withTrailingBoundary(List<String> lines) {
        if (!lines.getLast().isEmpty()) {
            List<String> result = new ArrayList<>(lines.size() + 1);
            result.addAll(lines);
            result.add("");
            return result;
        }
        return lines;
    }

    /** Set the transient status layer. Empty list clears it. */
    default void setLiveStatus(List<String> lines) {
        // no-op by default
    }

    /** Clear the status layer. */
    default void clearLiveStatus() {
        // no-op by default
    }

    /**
     * Atomically commit scrollback lines and update live status in one repaint.
     * Falls back to sequential calls for implementations without a live region.
     */
    default void commitScrollbackAndSetStatus(List<String> scrollbackLines,
                                              List<String> newLiveStatus) {
        scrollback(scrollbackLines);
        setLiveStatus(newLiveStatus);
    }

    /** Set the modal layer (supersedes status while non-empty). */
    default void setLiveModal(List<String> lines) {
        // no-op by default
    }

    /** Clear the modal layer (restores status visibility). */
    default void clearLiveModal() {
        // no-op by default
    }

    /**
     * Synchronously clear all transient UI layers before terminal ownership
     * changes. This is stronger than clearing status/modal individually: live
     * renderers may also need to reset their display diff state so no stale
     * frame leaks into the next prompt.
     */
    default void clearTransientUi() {
        clearLiveModal();
        clearLiveStatus();
    }

    /** Current terminal column count, with a sane minimum. */
    int width();

    /** Current terminal row count, with a sane minimum. */
    int height();

    /**
     * Show or hide the OS cursor. Calls nest: each {@code false} increments
     * a hide-depth counter, each {@code true} decrements it. The cursor is
     * only made visible when the counter returns to zero.
     */
    default void setCursorVisible(boolean visible) {
        // no-op by default
    }

    /** Flush any buffered output. */
    void flush();

    /**
     * Release terminal modifications and leave the cursor on a fresh
     * line below the last scrollback row.
     */
    default void shutdown() {
        // no-op by default
    }
}
