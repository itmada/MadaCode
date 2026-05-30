package madacode.tui.inline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineEditorTest {

    @Test
    void insertAppendsAtCursorAndAdvancesCursor() {
        LineEditor editor = new LineEditor();

        assertTrue(editor.insert("abc"));
        assertEquals("abc", editor.text());
        assertEquals(3, editor.cursor());

        editor.moveLeft();
        assertTrue(editor.insert("X"));
        assertEquals("abXc", editor.text());
        assertEquals(3, editor.cursor());
    }

    @Test
    void insertEmptyOrNullDoesNotChangeText() {
        LineEditor editor = new LineEditor();
        editor.set("abc", 1);

        assertFalse(editor.insert(""));
        assertFalse(editor.insert(null));
        assertEquals("abc", editor.text());
        assertEquals(1, editor.cursor());
    }

    @Test
    void backspaceDeletesBeforeCursorAndRespectsStartBoundary() {
        LineEditor editor = new LineEditor();
        editor.set("abc", 2);

        assertTrue(editor.backspace());
        assertEquals("ac", editor.text());
        assertEquals(1, editor.cursor());

        editor.home();
        assertFalse(editor.backspace());
        assertEquals("ac", editor.text());
        assertEquals(0, editor.cursor());
    }

    @Test
    void deleteRemovesAtCursorAndRespectsEndBoundary() {
        LineEditor editor = new LineEditor();
        editor.set("abc", 1);

        assertTrue(editor.delete());
        assertEquals("ac", editor.text());
        assertEquals(1, editor.cursor());

        editor.end();
        assertFalse(editor.delete());
        assertEquals("ac", editor.text());
        assertEquals(2, editor.cursor());
    }

    @Test
    void movementClampsAtEdges() {
        LineEditor editor = new LineEditor();
        editor.set("abc", 1);

        editor.moveLeft();
        editor.moveLeft();
        assertEquals(0, editor.cursor());

        editor.moveRight();
        editor.moveRight();
        editor.moveRight();
        editor.moveRight();
        assertEquals(3, editor.cursor());

        editor.home();
        assertEquals(0, editor.cursor());

        editor.end();
        assertEquals(3, editor.cursor());
    }

    @Test
    void setAndCursorClampToTextBounds() {
        LineEditor editor = new LineEditor();

        editor.set("abc", 99);
        assertEquals("abc", editor.text());
        assertEquals(3, editor.cursor());

        editor.cursor(-10);
        assertEquals(0, editor.cursor());

        editor.cursor(99);
        assertEquals(3, editor.cursor());

        editor.set(null, 4);
        assertEquals("", editor.text());
        assertEquals(0, editor.cursor());
    }
}
