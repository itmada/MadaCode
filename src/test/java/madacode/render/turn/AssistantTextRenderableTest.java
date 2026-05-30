package madacode.render.turn;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AssistantTextRenderableTest {

    @Test
    void shouldRenderEmptyWhenNoText() {
        AssistantTextRenderable r = new AssistantTextRenderable();
        assertTrue(r.render(80).isEmpty());
        assertTrue(r.drainCommittedLines().isEmpty());
        assertFalse(r.isFinalized());
    }

    @Test
    void shouldDrainCompleteLinesAndKeepPartialLive() {
        AssistantTextRenderable r = new AssistantTextRenderable();
        r.append("line1\nline2\npartial");

        // render() returns only the trailing partial
        List<String> live = r.render(80);
        assertEquals(1, live.size(), "only partial in live");
        assertTrue(strip(live.get(0)).contains("partial"), "partial content: " + live.get(0));
        assertTrue(live.get(0).contains("▌"), "cursor present: " + live.get(0));

        // committed lines come from drainCommittedLines
        // commonmark parses "line1\nline2\n" as single paragraph with soft break
        List<String> committed = r.drainCommittedLines();
        assertFalse(committed.isEmpty(), "should have committed lines");
        String joined = strip(String.join(" ", committed));
        assertTrue(joined.contains("line1"), "line1 present: " + joined);
        assertTrue(joined.contains("line2"), "line2 present: " + joined);

        // After drain, render still returns the partial
        List<String> afterDrain = r.render(80);
        assertEquals(1, afterDrain.size(), "partial persists after drain");
    }

    @Test
    void shouldApplyMarkdownToCommittedLines() {
        AssistantTextRenderable r = new AssistantTextRenderable();
        r.append("# Hello\n\n**bold** text\n\n```\ncode\n```\n");
        List<String> committed = r.drainCommittedLines();
        // Heading rendered (not raw "# Hello")
        assertTrue(committed.stream().anyMatch(l -> strip(l).contains("Hello") && !l.contains("# Hello")),
                "heading formatted: " + committed);
        // Bold applied — asterisks removed
        assertTrue(committed.stream().anyMatch(l -> strip(l).contains("bold text")),
                "bold present: " + committed);
        assertFalse(committed.stream().anyMatch(l -> l.contains("**bold**")),
                "asterisks stripped");
        // Code fence borders
        assertTrue(committed.stream().anyMatch(l -> l.contains("│") || l.contains("┌") || l.contains("└")),
                "code block borders: " + committed);
    }

    @Test
    void shouldRenderInlineCodeInCommittedLines() {
        AssistantTextRenderable r = new AssistantTextRenderable();
        r.append("run `npm install` now\n");
        List<String> committed = r.drainCommittedLines();
        assertTrue(committed.stream().anyMatch(l -> strip(l).contains("npm install")));
        assertFalse(committed.stream().anyMatch(l -> l.contains("`npm install`")));
    }

    @Test
    void shouldRenderCodeBlockPrefixInPartial() {
        AssistantTextRenderable r = new AssistantTextRenderable();
        r.append("```\ncode line\n");  // opens code block, then "code line" inside
        r.drainCommittedLines();        // drain "```" and "code line"
        r.append("partial code");       // still in code block
        List<String> live = r.render(80);
        assertEquals(1, live.size());
        assertTrue(live.get(0).contains("│"), "partial in code block should have border: " + live);
        assertTrue(strip(live.get(0)).contains("partial code"), "partial code content: " + live);
        assertTrue(live.get(0).contains("▌"), "cursor present: " + live);
    }

    @Test
    void shouldFlushPartialOnFinalize() {
        AssistantTextRenderable r = new AssistantTextRenderable();
        r.append("partial line without newline");
        r.finalizeText();
        // After finalize, rawBuffer is flushed into committedLines; render() returns empty
        assertTrue(r.render(80).isEmpty(), "nothing live after finalize");
        List<String> committed = r.drainCommittedLines();
        assertEquals(1, committed.size());
        assertTrue(strip(committed.get(0)).contains("partial line without newline"));
    }

    @Test
    void shouldDrainIncrementallyAcrossMultipleAppends() {
        AssistantTextRenderable r = new AssistantTextRenderable();
        r.append("line1\n");
        List<String> batch1 = r.drainCommittedLines();
        assertFalse(batch1.isEmpty(), "first batch should have content");

        r.append("line2\nline3\n");
        List<String> batch2 = r.drainCommittedLines();
        assertFalse(batch2.isEmpty(), "second batch should have content");

        // After drain, render() returns empty (no committed, no partial)
        List<String> live = r.render(80);
        assertTrue(live.isEmpty(), "no trailing partial after all lines end with \\n");
    }

    @Test
    void partialHeadingRenderedWithMarkdown() {
        AssistantTextRenderable r = new AssistantTextRenderable();
        r.append("## Section Title");
        List<String> live = r.render(80);
        assertEquals(1, live.size());
        assertTrue(strip(live.get(0)).contains("Section Title"), "heading styled: " + live);
        assertTrue(live.get(0).contains("▌"), "cursor: " + live);
    }

    @Test
    void noCursorAfterFinalized() {
        AssistantTextRenderable r = new AssistantTextRenderable();
        r.append("remaining text");
        r.finalizeText();
        // After finalize, content is in committedLines, render() is empty
        assertTrue(r.render(80).isEmpty(), "nothing live after finalize");
        List<String> committed = r.drainCommittedLines();
        assertEquals(1, committed.size());
        assertFalse(committed.get(0).contains("▌"), "no cursor in committed: " + committed);
    }

    @Test
    void drainsRenderedLinesFromCompleteMultilineTable() {
        AssistantTextRenderable r = new AssistantTextRenderable();
        r.append("| year | events |\n");
        r.append("|------|--------|\n");
        r.append("| 2021 | AlphaFold<br>GPT-3 |\n");
        r.append("after table\n");

        List<String> committed = r.drainCommittedLines(80);

        assertTrue(committed.stream().anyMatch(l -> strip(l).contains("AlphaFold")),
                "first cell line present: " + committed);
        assertTrue(committed.stream().anyMatch(l -> strip(l).contains("GPT-3")),
                "continuation cell line present: " + committed);
        assertTrue(committed.stream().anyMatch(l -> strip(l).contains("after table")),
                "boundary line present: " + committed);
    }

    @Test
    void finalizeDrainsAllRenderedLinesFromMultilineTable() {
        AssistantTextRenderable r = new AssistantTextRenderable();
        r.append("| year | events |\n|------|--------|\n| 2021 | AlphaFold<br>GPT-3 |");
        r.finalizeText();

        List<String> committed = r.drainCommittedLines(80);

        assertTrue(committed.stream().anyMatch(l -> strip(l).contains("AlphaFold")),
                "first cell line present after finalize: " + committed);
        assertTrue(committed.stream().anyMatch(l -> strip(l).contains("GPT-3")),
                "continuation cell line present after finalize: " + committed);
    }

    @Test
    void streamingAndOneShotTableRenderingAreConsistent() {
        String table = """
                | 年份 | 技术里程碑 | 主要应用领域 | 重要事件与产业影响 | 伦理与监管发展 |
                | --- | --- | --- | --- | --- |
                | 2021 | • AlphaFold 2实现蛋白质结构预测突破<br>• GPT-3发布，展示大语言模型潜力 | • 医疗影像诊断<br>• 自动驾驶测试 | • AI芯片需求激增<br>• 企业AI采用率提升 | • 关于AI偏见的讨论增多<br>• 数据隐私关注提升 |
                """;

        AssistantTextRenderable streaming = new AssistantTextRenderable();
        streaming.append("| 年份 | 技术里程碑 | 主要应用领域 | 重要事件与产业影响 | 伦理与监管发展 |\n");
        streaming.append("| --- | --- | --- | --- | --- |\n");
        streaming.append("| 2021 | • AlphaFold 2实现蛋白质结构预测突破<br>• GPT-3发布，展示大语言模型潜力 | • 医疗影像诊断<br>• 自动驾驶测试 | • AI芯片需求激增<br>• 企业AI采用率提升 | • 关于AI偏见的讨论增多<br>• 数据隐私关注提升 |\n");
        streaming.finalizeText();
        List<String> streamed = streaming.drainCommittedLines(80);

        AssistantTextRenderable oneShot = new AssistantTextRenderable();
        oneShot.append(table);
        oneShot.finalizeText();
        List<String> single = oneShot.drainCommittedLines(80);

        assertEquals(stripAll(single), stripAll(streamed),
                "streaming and one-shot rendered output should match");
    }

    @Test
    void lineByLineStreamingDoesNotFlushPartialTableEarly() {
        AssistantTextRenderable streaming = new AssistantTextRenderable();

        streaming.append("| 年份 | 技术里程碑 | 主要应用领域 | 重要事件与产业影响 | 伦理与监管发展 |\n");
        assertTrue(streaming.drainCommittedLines(80).isEmpty(),
                "table header alone should stay buffered until the table is complete");

        streaming.append("| --- | --- | --- | --- | --- |\n");
        assertTrue(streaming.drainCommittedLines(80).isEmpty(),
                "separator alone should still not flush a partial table");

        streaming.append("| 2021 | • AlphaFold 2实现蛋白质结构预测突破<br>• GPT-3发布，展示大语言模型潜力 | • 医疗影像诊断<br>• 自动驾驶测试 | • AI芯片需求激增<br>• 企业AI采用率提升 | • 关于AI偏见的讨论增多<br>• 数据隐私关注提升 |\n");
        assertTrue(streaming.drainCommittedLines(80).isEmpty(),
                "first data row should still be held during streaming");
        streaming.finalizeText();
        List<String> streamed = streaming.drainCommittedLines(80);

        AssistantTextRenderable oneShot = new AssistantTextRenderable();
        oneShot.append("""
                | 年份 | 技术里程碑 | 主要应用领域 | 重要事件与产业影响 | 伦理与监管发展 |
                | --- | --- | --- | --- | --- |
                | 2021 | • AlphaFold 2实现蛋白质结构预测突破<br>• GPT-3发布，展示大语言模型潜力 | • 医疗影像诊断<br>• 自动驾驶测试 | • AI芯片需求激增<br>• 企业AI采用率提升 | • 关于AI偏见的讨论增多<br>• 数据隐私关注提升 |
                """);
        oneShot.finalizeText();
        List<String> single = oneShot.drainCommittedLines(80);

        assertEquals(stripAll(single), stripAll(streamed),
                "line-by-line streaming should render the same complete table as one-shot input");
    }

    @Test
    void boxTableStreamingWaitsForFinalizationBeforeRendering() {
        AssistantTextRenderable streaming = new AssistantTextRenderable();

        streaming.append("│ 日期 │ 星期 │ 白天天气 │ 夜间天气 │ 最高温 │ 最低温 │\n");
        assertTrue(streaming.drainCommittedLines(100).isEmpty(),
                "box table header should not render as a standalone table");

        streaming.append("│ 05/19 │ 周二 │ 晴 │ 晴 │ 29° │ 16° │\n");
        assertTrue(streaming.drainCommittedLines(100).isEmpty(),
                "box table first data row should wait for finalization or boundary");

        streaming.append("│ 05/20 │ 周三 │ 多云 │ 阵雨 │ 27° │ 16° │\n");
        assertTrue(streaming.drainCommittedLines(100).isEmpty(),
                "box table should not be split into alternating two-line tables");

        streaming.finalizeText();
        List<String> streamed = streaming.drainCommittedLines(100);

        AssistantTextRenderable oneShot = new AssistantTextRenderable();
        oneShot.append("""
                │ 日期 │ 星期 │ 白天天气 │ 夜间天气 │ 最高温 │ 最低温 │
                │ 05/19 │ 周二 │ 晴 │ 晴 │ 29° │ 16° │
                │ 05/20 │ 周三 │ 多云 │ 阵雨 │ 27° │ 16° │
                """);
        oneShot.finalizeText();
        List<String> single = oneShot.drainCommittedLines(100);

        assertEquals(stripAll(single), stripAll(streamed),
                "streaming box table should render as one table, not alternating chunks");
    }

    @Test
    void multiRowTableNotSplitDuringLineByLineStreaming() {
        AssistantTextRenderable streaming = new AssistantTextRenderable();

        streaming.append("| H1 | H2 | H3 |\n");
        streaming.drainCommittedLines(80);
        streaming.append("|---|---|---|\n");
        streaming.drainCommittedLines(80);
        streaming.append("| R1 | D1 | I1 |\n");
        streaming.drainCommittedLines(80);
        streaming.append("| R2 | D2 | I2 |\n");
        streaming.drainCommittedLines(80);
        streaming.append("| R3 | D3 | I3 |\n");
        streaming.drainCommittedLines(80);

        streaming.append("\n");
        List<String> result = streaming.drainCommittedLines(80);

        long topBorders = result.stream()
                .filter(l -> strip(l).contains("┌")).count();
        assertEquals(1, topBorders,
                "should produce exactly one table, not split: " + result);
        assertTrue(result.stream().anyMatch(l -> strip(l).contains("R1")));
        assertTrue(result.stream().anyMatch(l -> strip(l).contains("R3")));
    }

    @Test
    void renderShowsTablePreviewDuringStreaming() {
        AssistantTextRenderable r = new AssistantTextRenderable();
        r.append("| H1 | H2 |\n|---|---|\n| D1 | D2 |\n");
        r.drainCommittedLines(80);

        List<String> live = r.render(80);
        assertFalse(live.isEmpty(), "live should show table preview");
        assertTrue(live.stream().anyMatch(l -> strip(l).contains("H1")),
                "preview has header: " + live);
        assertTrue(live.stream().anyMatch(l -> strip(l).contains("D1")),
                "preview has data: " + live);
        assertTrue(live.get(live.size() - 1).contains("▌"),
                "cursor on last preview line: " + live);
    }

    @Test
    void previewDisappearsAfterTableCommitted() {
        AssistantTextRenderable r = new AssistantTextRenderable();
        r.append("| H1 | H2 |\n|---|---|\n| D1 | D2 |\n");
        r.drainCommittedLines(80);
        assertFalse(r.render(80).isEmpty(), "preview visible before boundary");

        r.append("after table\n");
        List<String> committed = r.drainCommittedLines(80);
        assertTrue(committed.stream().anyMatch(l -> strip(l).contains("D1")),
                "table in scrollback: " + committed);

        assertTrue(r.render(80).isEmpty(),
                "preview gone after table committed to scrollback");
    }

    @Test
    void previewGrowsAsRowsArrive() {
        AssistantTextRenderable r = new AssistantTextRenderable();
        r.append("| H1 | H2 |\n|---|---|\n| R1 | D1 |\n");
        r.drainCommittedLines(80);
        List<String> preview1 = r.render(80);

        r.append("| R2 | D2 |\n");
        r.drainCommittedLines(80);
        List<String> preview2 = r.render(80);

        assertTrue(preview2.size() > preview1.size(),
                "preview should grow: " + preview1.size() + " -> " + preview2.size());
        assertTrue(preview2.stream().anyMatch(l -> strip(l).contains("R2")),
                "new row in preview: " + preview2);
    }

    private static String strip(String s) {
        return s.replaceAll("\033\\[[0-9;]*[a-zA-Z]", "");
    }

    private static List<String> stripAll(List<String> lines) {
        return lines.stream().map(AssistantTextRenderableTest::strip).toList();
    }
}
