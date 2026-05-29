package madacode.tui;

import org.jline.utils.WCWidth;

import java.util.ArrayList;
import java.util.List;

/**
 * Terminal-column helpers for layout code. ANSI escape sequences contribute
 * no display width; CJK and other wide code points count according to wcwidth.
 */
public final class TerminalText {

    private static final char ESC = 0x1B;
    private static final String ELLIPSIS = "…";

    private TerminalText() {}

    public static int displayWidth(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        int width = 0;
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == ESC) {
                i = skipAnsi(s, i);
                continue;
            }
            int end = clusterEnd(s, i);
            int w = clusterWidth(s, i, end);
            if (w > 0) {
                width += w;
            }
            i = end;
        }
        return width;
    }

    public static int clusterEnd(String s, int offset) {
        if (s == null || offset >= s.length()) {
            return offset;
        }
        int cp = s.codePointAt(offset);
        if (cp == ESC) {
            return skipAnsi(s, offset);
        }

        int i = offset + Character.charCount(cp);
        if (isRegionalIndicator(cp)) {
            if (i < s.length()) {
                int next = s.codePointAt(i);
                if (isRegionalIndicator(next)) {
                    return i + Character.charCount(next);
                }
            }
            return i;
        }

        if (isKeycapBase(cp)) {
            i = consumeVariationSelectors(s, i);
            if (i < s.length() && s.codePointAt(i) == 0x20E3) {
                return i + Character.charCount(0x20E3);
            }
            return consumeCombiningMarks(s, i);
        }

        boolean emojiLike = isEmojiBase(cp);
        i = consumeEmojiTrailers(s, i);
        if (!emojiLike) {
            return consumeCombiningMarks(s, i);
        }

        while (i < s.length()) {
            int zwj = s.codePointAt(i);
            if (zwj != 0x200D) {
                break;
            }
            int afterZwj = i + Character.charCount(zwj);
            if (afterZwj >= s.length()) {
                break;
            }
            int next = s.codePointAt(afterZwj);
            if (!isEmojiBase(next)) {
                break;
            }
            i = afterZwj + Character.charCount(next);
            i = consumeEmojiTrailers(s, i);
        }
        return i;
    }

    private static int clusterWidth(String s, int start, int end) {
        if (start >= end) {
            return 0;
        }
        int first = s.codePointAt(start);
        if (first == ESC) {
            return 0;
        }
        if (isRegionalIndicator(first) || isKeycapCluster(s, start, end)) {
            return 2;
        }
        if (isEmojiBase(first)) {
            return 2;
        }

        int width = 0;
        for (int i = start; i < end; ) {
            int cp = s.codePointAt(i);
            int w = WCWidth.wcwidth(cp);
            if (w > 0) {
                width += w;
            }
            i += Character.charCount(cp);
        }
        return width;
    }

    public static String truncateMiddle(String value, int maxColumns) {
        if (value == null) {
            return "";
        }
        String clean = value.replace('\n', ' ').replace('\r', ' ').strip();
        if (displayWidth(clean) <= maxColumns) {
            return clean;
        }
        if (maxColumns <= 0) {
            return "";
        }
        if (maxColumns == 1) {
            return ELLIPSIS;
        }
        int budget = maxColumns - 1;
        int leftBudget = budget / 2;
        int rightBudget = budget - leftBudget;
        return takeFromStart(clean, leftBudget) + ELLIPSIS + takeFromEnd(clean, rightBudget);
    }

    public static String fitEnd(String value, int maxColumns) {
        if (value == null) {
            return "";
        }
        String clean = value.replace('\n', ' ').replace('\r', ' ').strip();
        if (displayWidth(clean) <= maxColumns) {
            return clean;
        }
        if (maxColumns <= 0) {
            return "";
        }
        if (maxColumns == 1) {
            return ELLIPSIS;
        }
        return takeFromStart(clean, maxColumns - 1) + ELLIPSIS;
    }

    private static int skipAnsi(String s, int escIndex) {
        int i = escIndex + 1;
        if (i >= s.length()) {
            return i;
        }
        char c = s.charAt(i);
        if (c == '[') {
            // CSI sequence
            i++;
            while (i < s.length()) {
                c = s.charAt(i++);
                if (c >= '@' && c <= '~') {
                    break;
                }
            }
            return i;
        }
        if (c == ']' || c == 'P') {
            // OSC or DCS — skip until BEL (0x07) or ST (ESC \)
            i++;
            while (i < s.length()) {
                char ch = s.charAt(i++);
                if (ch == 0x07) return i;
                if (ch == ESC && i < s.length() && s.charAt(i) == '\\') {
                    return i + 1;
                }
            }
            return i;
        }
        return i + 1;
    }

    private static String takeFromStart(String s, int columns) {
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == ESC) {
                int end = skipAnsi(s, i);
                sb.append(s, i, end);
                i = end;
                continue;
            }
            int end = clusterEnd(s, i);
            int w = Math.max(0, clusterWidth(s, i, end));
            if (used + w > columns) {
                break;
            }
            sb.append(s, i, end);
            used += w;
            i = end;
        }
        return sb.toString();
    }

    private static String takeFromEnd(String s, int columns) {
        List<int[]> clusters = new ArrayList<>();
        for (int i = 0; i < s.length(); ) {
            int end = clusterEnd(s, i);
            clusters.add(new int[]{i, end, clusterWidth(s, i, end)});
            i = end;
        }
        int used = 0;
        int start = clusters.size();
        for (int i = clusters.size() - 1; i >= 0; i--) {
            int w = Math.max(0, clusters.get(i)[2]);
            if (used + w > columns) {
                break;
            }
            used += w;
            start = i;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < clusters.size(); i++) {
            int[] cluster = clusters.get(i);
            sb.append(s, cluster[0], cluster[1]);
        }
        return sb.toString();
    }

    private static int consumeEmojiTrailers(String s, int offset) {
        int i = consumeVariationSelectors(s, offset);
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            if (!isEmojiModifier(cp)) {
                break;
            }
            i += Character.charCount(cp);
            i = consumeVariationSelectors(s, i);
        }
        return i;
    }

    private static int consumeVariationSelectors(String s, int offset) {
        int i = offset;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            if (!isVariationSelector(cp)) {
                break;
            }
            i += Character.charCount(cp);
        }
        return i;
    }

    private static int consumeCombiningMarks(String s, int offset) {
        int i = offset;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int type = Character.getType(cp);
            if (type != Character.NON_SPACING_MARK
                    && type != Character.COMBINING_SPACING_MARK
                    && type != Character.ENCLOSING_MARK
                    && !isVariationSelector(cp)) {
                break;
            }
            i += Character.charCount(cp);
        }
        return i;
    }

    private static boolean isKeycapCluster(String s, int start, int end) {
        if (start >= end || !isKeycapBase(s.codePointAt(start))) {
            return false;
        }
        for (int i = start + Character.charCount(s.codePointAt(start)); i < end; ) {
            int cp = s.codePointAt(i);
            if (cp == 0x20E3) {
                return true;
            }
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean containsEmojiJoiner(String s, int start, int end) {
        for (int i = start; i < end; ) {
            int cp = s.codePointAt(i);
            if (cp == 0x200D) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean containsEmojiModifier(String s, int start, int end) {
        for (int i = start; i < end; ) {
            int cp = s.codePointAt(i);
            if (isEmojiModifier(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean containsVariationSelector16(String s, int start, int end) {
        for (int i = start; i < end; ) {
            int cp = s.codePointAt(i);
            if (cp == 0xFE0F) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    private static boolean isRegionalIndicator(int cp) {
        return cp >= 0x1F1E6 && cp <= 0x1F1FF;
    }

    private static boolean isKeycapBase(int cp) {
        return (cp >= '0' && cp <= '9') || cp == '#' || cp == '*';
    }

    private static boolean isVariationSelector(int cp) {
        return (cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0xE0100 && cp <= 0xE01EF);
    }

    private static boolean isEmojiModifier(int cp) {
        return cp >= 0x1F3FB && cp <= 0x1F3FF;
    }

    private static boolean isEmojiBase(int cp) {
        return (cp >= 0x1F000 && cp <= 0x1FAFF)
                || (cp >= 0x2600 && cp <= 0x27BF)
                || (cp >= 0x2300 && cp <= 0x23FF)
                || cp == 0x00A9
                || cp == 0x00AE
                || cp == 0x2122
                || cp == 0x2139
                || cp == 0x3030
                || cp == 0x303D
                || cp == 0x3297
                || cp == 0x3299;
    }
}
