package madacode.render.tool;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ToolProgressSnapshotTest {

    @Test
    void snapshotDefensivelyCopiesLinesAndClampsCounts() {
        List<ToolProgressLine> lines = new ArrayList<>();
        lines.add(ToolProgressLine.activity("one"));

        ToolProgressSnapshot snapshot = new ToolProgressSnapshot(lines, -1, -2);
        lines.add(ToolProgressLine.activity("two"));

        assertEquals(1, snapshot.lines().size());
        assertEquals(0, snapshot.droppedLineCount());
        assertEquals(0, snapshot.droppedActivityCount());
    }
}
