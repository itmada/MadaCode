package madacode.tool;

import madacode.longrunning.LongRunningTaskInitializer;

final class LongRunTaskUpdateToolSupport {

    private LongRunTaskUpdateToolSupport() {}

    static String deriveTaskTitle(String summary, String fallbackTitle) {
        if (fallbackTitle != null && !fallbackTitle.isBlank()) {
            return fallbackTitle.strip();
        }
        return LongRunningTaskInitializer.taskTitle(summary);
    }
}
