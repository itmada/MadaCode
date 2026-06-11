package madacode.longrunning;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

final class LongRunningLockManager {

    private static final Map<Path, Object> TASK_STATE_LOCKS = new ConcurrentHashMap<>();

    private final LongRunningTaskRepository repository;

    LongRunningLockManager(LongRunningTaskRepository repository) {
        this.repository = repository;
    }

    LongRunningTaskLease acquireExecutionLease(String taskId) {
        String safeTaskId = repository.validateTaskId(taskId);
        Path directory = repository.validateTaskDirectory(safeTaskId);
        try {
            FileChannel channel = FileChannel.open(
                    directory.resolve(LongRunningTaskRepository.EXECUTION_LOCK_FILE),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            return LongRunningTaskLease.tryAcquire(safeTaskId, channel);
        } catch (IOException exception) {
            throw new LongRunningTaskStoreException(
                    "Failed to open execution lease for task " + safeTaskId, exception);
        }
    }

    <T> T withTaskMetadataLock(String taskId, Supplier<T> action) {
        String safeTaskId = repository.validateTaskId(taskId);
        Path lockFile = repository.taskDirectoryPath(safeTaskId).resolve(LongRunningTaskRepository.TASK_STATE_LOCK_FILE);
        Object processLock = TASK_STATE_LOCKS.computeIfAbsent(
                lockFile.toAbsolutePath().normalize(), ignored -> new Object());
        synchronized (processLock) {
            boolean restoreInterrupt = Thread.interrupted();
            try (FileChannel channel = FileChannel.open(
                    lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var ignored = channel.lock()) {
                return action.get();
            } catch (IOException exception) {
                throw new LongRunningTaskStoreException(
                        "Failed to lock task metadata for " + safeTaskId, exception);
            } finally {
                if (restoreInterrupt) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
