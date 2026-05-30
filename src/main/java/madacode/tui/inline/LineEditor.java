package madacode.tui.inline;

public final class LineEditor {

    private final StringBuilder text = new StringBuilder();
    private int cursor;

    public LineEditor() {
    }

    public String text() {
        return text.toString();
    }

    public int cursor() {
        return cursor;
    }

    public void set(String value, int cursor) {
        text.setLength(0);
        if (value != null) {
            text.append(value);
        }
        this.cursor = clamp(cursor, text.length());
    }

    public boolean insert(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        text.insert(cursor, value);
        cursor += value.length();
        return true;
    }

    public boolean backspace() {
        if (cursor <= 0 || text.isEmpty()) {
            return false;
        }
        text.deleteCharAt(cursor - 1);
        cursor--;
        return true;
    }

    public boolean delete() {
        if (cursor < 0 || cursor >= text.length()) {
            return false;
        }
        text.deleteCharAt(cursor);
        return true;
    }

    public void moveLeft() {
        if (cursor > 0) {
            cursor--;
        }
    }

    public void moveRight() {
        if (cursor < text.length()) {
            cursor++;
        }
    }

    public void home() {
        cursor = 0;
    }

    public void end() {
        cursor = text.length();
    }

    public void cursor(int cursor) {
        this.cursor = clamp(cursor, text.length());
    }

    private static int clamp(int value, int max) {
        if (value < 0) {
            return 0;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
