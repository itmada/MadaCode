package madacode.render.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

public interface ToolDisplayAdapter {

    String toolName();

    ToolDisplay renderStart(ObjectNode input);

    default ToolDisplay renderRunning(ObjectNode input, ToolProgressSnapshot progress) {
        return renderStart(input);
    }

    default ToolDisplay renderRunning(ObjectNode input, List<ToolProgressLine> progressLines) {
        return renderRunning(input, ToolProgressSnapshot.of(progressLines));
    }

    default ToolDisplay renderQueued(ObjectNode input) {
        ToolDisplay display = renderStart(input);
        return new ToolDisplay(display.title(), "Waiting...", List.of(), DisplayStatus.RUNNING);
    }

    default String activityDescription(ObjectNode input) {
        ToolDisplay display = renderStart(input);
        return display.summary().isBlank() ? display.title() : display.summary();
    }

    ToolDisplay renderResult(ObjectNode input, boolean success, String output, long durationMs);

    /**
     * Verbose variant — adapters return more detail lines. The default
     * delegates to {@link #renderResult}; adapters with truncation logic
     * override to raise their {@code maxLines} ceiling.
     */
    default ToolDisplay renderResultVerbose(ObjectNode input, boolean success, String output, long durationMs) {
        return renderResult(input, success, output, durationMs);
    }

    default ToolDisplay renderDenied(ObjectNode input, String reason, long durationMs) {
        ToolDisplay display = renderStart(input);
        return ToolDisplay.denied(display.title(), "Permission denied", List.of());
    }

    default ToolDisplay renderError(ObjectNode input, String output, long durationMs) {
        ToolDisplay display = renderResult(input, false, output, durationMs);
        return new ToolDisplay(
                display.title(),
                display.summary(),
                display.detailLines(),
                display.verboseDetailLines(),
                DisplayStatus.FAILED);
    }

    default ToolDisplay renderSuccess(ObjectNode input, String output, long durationMs) {
        ToolDisplay display = renderResult(input, true, output, durationMs);
        return new ToolDisplay(
                display.title(),
                display.summary(),
                display.detailLines(),
                display.verboseDetailLines(),
                DisplayStatus.SUCCESS);
    }
}
