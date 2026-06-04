package madacode.longrunning;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunningMonitorRendererTest {

    private final LongRunningMonitorRenderer renderer = new LongRunningMonitorRenderer();

    @Test
    void rendersCompactMonitorWithRules() {
        List<String> lines = renderer.render(new LongRunningMonitorSnapshot(
                "task-20260604-xxxx",
                "RUNNING",
                "worker-003",
                3,
                12,
                "F03 用户认证",
                "Running: ./mvnw test",
                List.of("14:21 Read backend/pom.xml"),
                false));

        assertEquals("────────────────────────────────────────────────────────", lines.getFirst());
        assertEquals("Long-running: RUNNING", lines.get(1));
        assertEquals("Task task-20260604-xxxx · Worker worker-003 · Cycle 3/12", lines.get(2));
        assertTrue(lines.contains("F03 用户认证"));
        assertTrue(lines.contains("Running: ./mvnw test"));
        assertTrue(lines.contains("14:21 Read backend/pom.xml"));
        assertTrue(lines.contains("Esc / Ctrl+C interrupt"));
        assertEquals("────────────────────────────────────────────────────────", lines.getLast());
    }

    @Test
    void rendersFallbacksForEmptySnapshot() {
        List<String> lines = renderer.render(new LongRunningMonitorSnapshot(
                "task-empty",
                "RUNNING",
                null,
                null,
                null,
                null,
                null,
                List.of(),
                false));

        assertTrue(lines.contains("Task task-empty · Worker starting... · Cycle ?/?"));
        assertTrue(lines.contains("Selecting target..."));
        assertTrue(lines.contains("Working..."));
        assertTrue(lines.contains("Waiting for worker events..."));
    }

    @Test
    void rendersInterruptingPrompt() {
        List<String> lines = renderer.render(new LongRunningMonitorSnapshot(
                "task-stop",
                "RUNNING",
                null,
                1,
                1,
                null,
                null,
                List.of(),
                true));

        assertTrue(lines.contains("Stopping current worker safely..."));
        assertTrue(lines.contains("Interrupt requested; stopping current worker..."));
    }
}
