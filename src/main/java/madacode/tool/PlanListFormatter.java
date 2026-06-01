package madacode.tool;

import madacode.core.session.ConversationSession;
import madacode.plan.PlanItem;
import madacode.plan.PlanStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class PlanListFormatter {

    private PlanListFormatter() {}

    static String format(List<PlanItem> items, ConversationSession session) {
        if (items.isEmpty()) {
            return "(no plan items)";
        }

        List<String> lines = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            lines.add(formatRow(items.get(i), session, i, items.size()));
        }
        return String.join("\n", lines);
    }

    private static String formatRow(PlanItem item, ConversationSession session, int index, int size) {
        Set<String> remainingBlockers = session.plan().validateCanStart(item);
        StringBuilder sb = new StringBuilder();
        sb.append(rail(index, size)).append(" ");
        sb.append(String.format(java.util.Locale.ROOT, "%02d", index + 1)).append(" ");
        sb.append(statusSymbol(item.status())).append(" ");
        sb.append("[").append(item.status()).append("] ");
        sb.append(item.id()).append("  ").append(item.title());
        if (!remainingBlockers.isEmpty()) {
            sb.append(" (blocked)");
        }
        if (!item.blockedBy().isEmpty()) {
            sb.append(" (blocked by: ").append(String.join(", ", item.blockedBy()));
            if (!remainingBlockers.isEmpty()) {
                sb.append(", still blocked by: ").append(String.join(", ", remainingBlockers));
            }
            sb.append(")");
        }
        return sb.toString();
    }

    private static String rail(int index, int size) {
        if (size == 1) {
            return "╭";
        }
        if (index == 0) {
            return "╭";
        }
        if (index == size - 1) {
            return "╰";
        }
        return "│";
    }

    private static String statusSymbol(PlanStatus status) {
        return switch (status) {
            case COMPLETED -> "✓";
            case IN_PROGRESS -> "●";
            case PENDING -> "○";
        };
    }
}
