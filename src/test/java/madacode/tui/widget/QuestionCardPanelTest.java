package madacode.tui.widget;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestionCardPanelTest {

    private static List<String> renderPlain(QuestionCardPanel.View view) {
        return QuestionCardPanel.render(view, 80).stream()
                .map(l -> l.toString()) // AttributedString.toString() is the plain (style-free) text
                .toList();
    }

    private static boolean anyLine(List<String> lines, String needle) {
        return lines.stream().anyMatch(l -> l.contains(needle));
    }

    @Test
    void headerShowsTitleAndProgress() {
        var view = new QuestionCardPanel.View(
                "Auth method", "Which approach?", "1/3", false,
                List.of(new QuestionCardPanel.OptionRow("JWT", "stateless", true, true, false)),
                "", 0, false, "footer");
        List<String> lines = renderPlain(view);
        assertTrue(anyLine(lines, "── Auth method"), "header divider with title");
        assertTrue(anyLine(lines, "1/3"), "progress marker");
        assertTrue(anyLine(lines, "Which approach?"), "question text");
    }

    @Test
    void singleSelectUsesRadioGlyphsAndFocusCursor() {
        var view = new QuestionCardPanel.View(
                "Q", "pick", "", false,
                List.of(
                        new QuestionCardPanel.OptionRow("Alpha", "", true, true, false),
                        new QuestionCardPanel.OptionRow("Beta", "", false, false, false)),
                "", 0, false, "f");
        List<String> lines = renderPlain(view);
        assertTrue(anyLine(lines, "❯ ● Alpha"), "focused, chosen radio");
        assertTrue(anyLine(lines, "○ Beta"), "unchosen radio");
    }

    @Test
    void multiSelectUsesCheckboxGlyphs() {
        var view = new QuestionCardPanel.View(
                "Q", "pick", "", true,
                List.of(
                        new QuestionCardPanel.OptionRow("Alpha", "", true, false, false),
                        new QuestionCardPanel.OptionRow("Beta", "", false, true, false)),
                "", 0, false, "f");
        List<String> lines = renderPlain(view);
        assertTrue(anyLine(lines, "◉ Alpha"), "checked checkbox");
        assertTrue(anyLine(lines, "❯ ◯ Beta"), "focused unchecked checkbox");
    }

    @Test
    void recommendedOptionGetsStarTag() {
        var view = new QuestionCardPanel.View(
                "Q", "pick", "", false,
                List.of(new QuestionCardPanel.OptionRow("Alpha", "best", true, true, true)),
                "", 0, false, "f");
        assertTrue(anyLine(renderPlain(view), "★"), "recommended star");
    }

    @Test
    void textRowShowsPlaceholderWhenEmptyAndUnfocused() {
        var view = new QuestionCardPanel.View(
                "Q", "pick", "", false,
                List.of(new QuestionCardPanel.OptionRow("Alpha", "", false, true, false)),
                "", 0, false, "f");
        assertTrue(anyLine(renderPlain(view), "Add a note"), "placeholder for empty note");
    }

    @Test
    void textRowShowsTypedValue() {
        var view = new QuestionCardPanel.View(
                "Q", "pick", "", false,
                List.of(new QuestionCardPanel.OptionRow("Alpha", "", false, false, false)),
                "use RS256", "use RS256".length(), true, "f");
        assertTrue(anyLine(renderPlain(view), "use RS256"), "typed note shown");
    }

    @Test
    void looksRecommendedDetectsEnglishAndChinese() {
        assertTrue(QuestionCardPanel.looksRecommended("JWT (Recommended)", ""));
        assertTrue(QuestionCardPanel.looksRecommended("JWT", "推荐方案"));
    }
}
