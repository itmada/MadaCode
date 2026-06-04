package madacode.cli.slash;

import madacode.tui.widget.ChoicePrompt;

import java.util.List;
import java.util.Objects;

final class SlashChoiceModels {

    private static final String FOOTER = "↑/↓ select   Enter confirm   Esc cancel";
    private static final String CURRENT_META = "current";

    private SlashChoiceModels() {}

    static ChoicePrompt.Model<String> choice(
            String title,
            String subtitle,
            List<String> items,
            String currentValue) {
        int currentIndex = currentIndex(items, currentValue);
        List<ChoicePrompt.Option<String>> options = items.stream()
                .map(item -> new ChoicePrompt.Option<>(
                        item,
                        item,
                        "",
                        Objects.equals(item, currentValue) ? CURRENT_META : ""))
                .toList();
        return new ChoicePrompt.Model<>(
                title, subtitle, options, FOOTER, currentIndex);
    }

    private static int currentIndex(List<String> items, String currentValue) {
        if (currentValue == null || currentValue.isBlank()) {
            return 0;
        }
        for (int i = 0; i < items.size(); i++) {
            if (Objects.equals(items.get(i), currentValue)) {
                return i;
            }
        }
        return 0;
    }
}
