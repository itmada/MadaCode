package madacode.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ReadFileState {

    public record ReadRecord(long mtimeMillis, boolean isPartialView) {}

    private final ConcurrentHashMap<Path, ReadRecord> state = new ConcurrentHashMap<>();

    /** Called by FileReadTool after a successful read. */
    public void record(Path path, boolean isPartialView) {
        Path normalized = path.toAbsolutePath().normalize();
        long mtime = currentMtime(normalized);
        state.put(normalized, new ReadRecord(mtime, isPartialView));
    }

    /** Called by FileEditTool/FileWriteTool after a successful write. */
    public void updateAfterWrite(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        long mtime = currentMtime(normalized);
        state.put(normalized, new ReadRecord(mtime, false));
    }

    public Optional<ReadRecord> get(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        return Optional.ofNullable(state.get(normalized));
    }

    /** Check if file has been modified since last read. Returns null if OK, or error message if stale. */
    public String checkStaleness(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        ReadRecord record = state.get(normalized);
        if (record == null) {
            return "File has not been read yet. Read the file before editing.";
        }
        if (record.isPartialView()) {
            return "File was only partially read. Read the full file before editing.";
        }
        long currentMtime = currentMtime(normalized);
        if (currentMtime != record.mtimeMillis()) {
            return "File has been modified since it was last read. Read it again before editing.";
        }
        return null;
    }

    private static long currentMtime(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }
}
