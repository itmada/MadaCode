package madacode.longrunning;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;

/**
 * Exclusive, process-wide ownership of a long-running task execution.
 *
 * <p>The lease is backed by an operating-system file lock. Keeping the
 * channel open for the launcher lifetime prevents a second MadaCode process
 * from launching workers for the same task.
 */
public final class LongRunningTaskLease implements AutoCloseable {

    private final String taskId;
    private final FileChannel channel;
    private final FileLock lock;

    LongRunningTaskLease(String taskId, FileChannel channel, FileLock lock) {
        this.taskId = taskId;
        this.channel = channel;
        this.lock = lock;
    }

    public String taskId() {
        return taskId;
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
            // Closing the channel below also releases the lock.
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // The task is no longer executable through this lease instance.
        }
    }

    static LongRunningTaskLease tryAcquire(String taskId, FileChannel channel) {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw unavailable(taskId);
            }
            return new LongRunningTaskLease(taskId, channel, lock);
        } catch (OverlappingFileLockException exception) {
            closeQuietly(channel);
            throw unavailable(taskId);
        } catch (IOException exception) {
            closeQuietly(channel);
            throw new LongRunningTaskStoreException(
                    "Failed to acquire execution lease for task " + taskId, exception);
        }
    }

    private static LongRunningTaskLeaseUnavailableException unavailable(String taskId) {
        return new LongRunningTaskLeaseUnavailableException(
                "Task " + taskId + " is already owned by another long-running launcher.");
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // Nothing else can be done while acquisition is failing.
        }
    }
}
