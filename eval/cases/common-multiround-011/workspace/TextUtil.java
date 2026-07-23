/** Small text helpers. */
public final class TextUtil {

    /** Existing behavior; must keep working. */
    public static String initials(String s) {
        StringBuilder sb = new StringBuilder();
        for (String word : s.trim().split("\\s+")) {
            if (!word.isEmpty()) {
                sb.append(Character.toUpperCase(word.charAt(0)));
            }
        }
        return sb.toString();
    }
}
