package madacode.longrunning;

import java.nio.file.Path;

/**
 * Immutable context representing an initialized long-running execution task.
 *
 * <p>Produced by {@link LongRunningTaskInitializer#ensureExecutionTask} and
 * consumed by tool execution and session wiring.
 *
 * @param taskId       the unique task identifier
 * @param taskDirectory the validated canonical task directory
 * @param metadata     the task metadata loaded from or written to the store
 */
public record LongRunningTaskContext(
        String taskId,
        Path taskDirectory,
        LongRunningTaskMetadata metadata) {}
