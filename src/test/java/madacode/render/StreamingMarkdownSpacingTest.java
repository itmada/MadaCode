package madacode.render;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Block spacing must not depend on how the network happens to chunk the token
 * stream. These tests feed the same markdown through {@link StreamingMarkdownDocument}
 * at several chunk granularities (mimicking the real progressive drain to
 * scrollback) and assert the committed output is byte-identical to the
 * all-at-once finalized render.
 */
class StreamingMarkdownSpacingTest {

    private static final int WIDTH = 80;

    /** Render everything in one shot (reference output). */
    private static List<String> finalized(String md) {
        StreamingMarkdownDocument doc = new StreamingMarkdownDocument();
        doc.append(md);
        return strip(doc.layout(WIDTH, true).permanentLines());
    }

    /** Stream {@code md} in fixed-size chunks, draining committed lines as a turn would. */
    private static List<String> streamed(String md, int chunk) {
        StreamingMarkdownDocument doc = new StreamingMarkdownDocument();
        List<String> scrollback = new ArrayList<>();
        for (int i = 0; i < md.length(); i += chunk) {
            doc.append(md.substring(i, Math.min(md.length(), i + chunk)));
            scrollback.addAll(doc.layout(WIDTH, false).permanentLines());
        }
        scrollback.addAll(doc.layout(WIDTH, true).permanentLines());
        return strip(scrollback);
    }

    private static List<String> strip(List<String> lines) {
        return lines.stream().map(l -> l.replaceAll("\\[[;\\d]*m", "")).toList();
    }

    private void assertChunkInvariant(String md) {
        List<String> reference = finalized(md);
        for (int chunk : new int[] {1, 2, 3, 7, 13, 1000}) {
            assertEquals(reference, streamed(md, chunk),
                    "streaming with chunk=" + chunk + " must match the finalized render");
        }
    }

    @Test
    void consecutiveParagraphsKeepOneBlankRegardlessOfChunking() {
        assertChunkInvariant("第一段。\n\n第二段。\n\n第三段。\n");
    }

    @Test
    void headingParagraphAndListSpacingIsChunkIndependent() {
        assertChunkInvariant(
                "# 标题\n正文第一段。\n\n## 小标题\n下面是要点：\n- 第一点\n- 第二点\n\n结束语。\n");
    }

    @Test
    void tightListItemsStayTogetherWhenStreamed() {
        List<String> out = streamed("要点：\n\n- a\n- b\n- c\n\n完。\n", 1);
        assertEquals(List.of(
                "要点：",
                "",
                "• a",
                "• b",
                "• c",
                "",
                "完。"
        ), out);
    }

    @Test
    void fencedCodeBlockStreamsWithFenceHeaderAndSurroundingBlanks() {
        List<String> out = streamed("说明：\n\n```java\nint x = 1;\nint y = 2;\n```\n\n完。\n", 1);
        assertEquals(List.of(
                "说明：",
                "",
                "╭─ java",
                "│ int x = 1;",
                "│ int y = 2;",
                "╰─",
                "",
                "完。"
        ), out);
    }

    @Test
    void tableHoldsTogetherAndSpacingIsChunkIndependent() {
        assertChunkInvariant("说明：\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n完。\n");
    }
}
