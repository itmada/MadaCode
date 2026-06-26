package madacode.longrunning;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Evaluates whether a long-running task is ready to enter RUNNING.
 */
public final class LongRunningExecutionReadiness {

    private final LongRunningTaskStore store;

    public LongRunningExecutionReadiness(LongRunningTaskStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    public Result evaluate(String taskId) {
        Objects.requireNonNull(taskId, "taskId");

        Path featureListPath = store.taskDirectoryPath(taskId).resolve(LongRunningTaskRepository.FEATURE_LIST_FILE);
        List<String> unmetItems = new ArrayList<>();
        try {
            List<FeatureItem> features = store.readFeatureList(taskId);
            if (features.isEmpty()) {
                unmetItems.add("feature_list.json is empty");
            }
        } catch (LongRunningTaskStoreException exception) {
            String reason = exception.getMessage();
            if (reason == null || reason.isBlank()) {
                reason = exception.getClass().getSimpleName();
            }
            unmetItems.add("feature_list.json is malformed at " + featureListPath + ": " + reason.strip());
        }
        return new Result(List.copyOf(unmetItems));
    }

    public record Result(List<String> unmetItems) {
        public Result {
            unmetItems = List.copyOf(Objects.requireNonNull(unmetItems, "unmetItems"));
        }

        public boolean isReady() {
            return unmetItems.isEmpty();
        }

        public String summary() {
            if (isReady()) {
                return "Long-running execution is ready.";
            }
            return "Cannot start long-running workers until readiness checks pass: "
                    + String.join("; ", unmetItems);
        }
    }
}
