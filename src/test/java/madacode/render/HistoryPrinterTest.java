package madacode.render;

import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.tui.TextScreen;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoryPrinterTest {

    @Test
    void userSingleLine() {
        String out = print(Message.user("hello"));
        assertTrue(out.contains("hello"), "should contain user text; got: " + out);
    }

    @Test
    void userMultiLineRendersAllLines() {
        String out = print(Message.user("line one\nline two\nline three"));
        assertTrue(out.contains("line one"), "should contain first line; got: " + out);
        assertTrue(out.contains("line two"), "should contain second line; got: " + out);
        assertTrue(out.contains("line three"), "should contain third line; got: " + out);
    }

    @Test
    void userMultiLineOnlyFirstLineGetsPromptMarker() {
        String out = print(Message.user("line one\nline two\nline three"));
        assertEquals(1, count(out, "❯"),
                "only the first physical line should get the prompt marker; got: " + out);
        assertTrue(out.contains("\n  line two"), "continuation line should be indented; got: " + out);
        assertTrue(out.contains("\n  line three"), "continuation line should be indented; got: " + out);
    }

    @Test
    void assistantTextRendered() {
        String out = print(Message.assistant("hi there"));
        assertTrue(out.contains("hi there"), "should contain assistant text; got: " + out);
    }

    @Test
    void assistantTableUsesScreenWidth() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TextScreen screen = new TextScreen(new PrintStream(bytes, true, StandardCharsets.UTF_8), 40, 24);
        HistoryPrinter printer = new HistoryPrinter(screen, null);
        printer.printAll(List.of(Message.assistant("""
                | year | events |
                |------|--------|
                | 2021 | AlphaFold GPT-3 multimodal agents regulation |
                """)));

        String out = strip(bytes.toString(StandardCharsets.UTF_8));
        assertTrue(out.contains("AlphaFold"), "table content should render; got: " + out);
        for (String line : out.split("\\R")) {
            assertTrue(displayWidth(line) <= 40, "line too wide: " + line);
        }
    }

    @Test
    void systemMessageRendered() {
        String out = print(Message.system("note"));
        assertTrue(out.contains("note"), "should contain system text; got: " + out);
    }

    @Test
    void orphanToolResultSkipped() {
        ContentBlock.ToolResultBlock orphan = new ContentBlock.ToolResultBlock(
                "missing-id", "output", true, 100);
        String out = print(Message.assistant(List.of(orphan)));
        assertFalse(out.contains("output"),
                "orphan tool result should not render; got: " + out);
    }

    @Test
    void printFromSkipsEarlierMessages() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TextScreen screen = new TextScreen(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        HistoryPrinter printer = new HistoryPrinter(screen, null);

        List<Message> messages = List.of(
                Message.user("first"),
                Message.user("second"),
                Message.user("third"));
        printer.printFrom(messages, 1);

        String out = strip(bytes.toString(StandardCharsets.UTF_8));
        assertFalse(out.contains("first"), "printFrom(1) should skip first message; got: " + out);
        assertTrue(out.contains("second"), "printFrom(1) should include second message; got: " + out);
        assertTrue(out.contains("third"), "printFrom(1) should include third message; got: " + out);
    }

    @Test
    void assistantWideTableFallsBackAndRespectsWidth() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TextScreen screen = new TextScreen(new PrintStream(bytes, true, StandardCharsets.UTF_8), 80, 24);
        HistoryPrinter printer = new HistoryPrinter(screen, null);
        printer.printAll(List.of(Message.assistant("""
                | 年份 | 技术里程碑 | 主要应用领域 | 重要事件与产业影响 | 伦理与监管发展 |
                | --- | --- | --- | --- | --- |
                | 2021 | • AlphaFold 2实现蛋白质结构预测突破<br>• GPT-3发布，展示大语言模型潜力 | • 医疗影像诊断<br>• 自动驾驶测试 | • AI芯片需求激增<br>• 企业AI采用率提升 | • 关于AI偏见的讨论增多<br>• 数据隐私关注提升 |
                """)));

        String out = strip(bytes.toString(StandardCharsets.UTF_8));
        assertTrue(out.contains("2021"), "should include title row: " + out);
        assertTrue(out.contains("技术里程碑"), "should include key text: " + out);
        for (String line : out.split("\\R")) {
            assertTrue(displayWidth(line) <= 80, "line too wide: " + line);
        }
    }

    private static String print(Message message) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        TextScreen screen = new TextScreen(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        HistoryPrinter printer = new HistoryPrinter(screen, null);
        printer.printAll(List.of(message));
        return strip(bytes.toString(StandardCharsets.UTF_8));
    }

    private static String strip(String s) {
        return s.replaceAll("\\x1B\\[[0-9;]*[a-zA-Z]", "");
    }

    private static int count(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private static int displayWidth(String s) {
        int width = 0;
        for (int offset = 0; offset < s.length(); ) {
            int cp = s.codePointAt(offset);
            width += Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN ? 2 : 1;
            offset += Character.charCount(cp);
        }
        return width;
    }
}
