package madacode.cli;

import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Buffer;
import org.jline.reader.Expander;
import org.jline.reader.Highlighter;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.MaskingCallback;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;
import org.jline.reader.Widget;
import org.jline.terminal.MouseEvent;
import org.jline.terminal.Terminal;
import org.jline.utils.AttributedString;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

/**
 * Minimal LineReader stub for unit tests. Only readLine(String) does real work;
 * all other methods throw or return harmless defaults.
 */
class FakeLineReader implements LineReader {

    private final Queue<String> responses = new LinkedList<>();
    private String lastPrompt;

    FakeLineReader queueResponse(String response) {
        responses.add(response);
        return this;
    }

    String lastPrompt() {
        return lastPrompt;
    }

    // ---- the only method under test ----------------------------------------

    @Override
    public String readLine(String prompt) {
        lastPrompt = prompt;
        return responses.poll();
    }

    // ---- delegating overloads to readLine(String) --------------------------

    @Override public String readLine() { return readLine((String) null); }
    @Override public String readLine(Character maskingChar) { return readLine((String) null); }
    @Override public String readLine(String prompt, Character maskingChar) { return readLine(prompt); }
    @Override public String readLine(String prompt, Character maskingChar, String buffer) { return readLine(prompt); }
    @Override public String readLine(String prompt, String rightPrompt, Character maskingChar, String buffer) { return readLine(prompt); }
    @Override public String readLine(String prompt, String rightPrompt, MaskingCallback maskingCallback, String buffer) { return readLine(prompt); }

    // ---- stubs for unused interface methods --------------------------------

    @Override public Map<String, KeyMap<Binding>> defaultKeyMaps() { return Collections.emptyMap(); }
    @Override public void printAbove(String str) {}
    @Override public void printAbove(AttributedString str) {}
    @Override public boolean isReading() { return false; }
    @Override public LineReader variable(String name, Object value) { return this; }
    @Override public LineReader option(Option option, boolean value) { return this; }
    @Override public void callWidget(String name) {}
    @Override public Map<String, Object> getVariables() { return Collections.emptyMap(); }
    @Override public Object getVariable(String name) { return null; }
    @Override public void setVariable(String name, Object value) {}
    @Override public boolean isSet(Option option) { return false; }
    @Override public void setOpt(Option option) {}
    @Override public void unsetOpt(Option option) {}
    @Override public Terminal getTerminal() { return null; }
    @Override public Map<String, Widget> getWidgets() { return new HashMap<>(); }
    @Override public Map<String, Widget> getBuiltinWidgets() { return Collections.emptyMap(); }
    @Override public Buffer getBuffer() { return null; }
    @Override public String getAppName() { return "fake"; }
    @Override public void runMacro(String macro) {}
    @Override public MouseEvent readMouseEvent() { return null; }
    @Override public History getHistory() { return null; }
    @Override public Parser getParser() { return null; }
    @Override public Highlighter getHighlighter() { return null; }
    @Override public Expander getExpander() { return null; }
    @Override public Map<String, KeyMap<Binding>> getKeyMaps() { return Collections.emptyMap(); }
    @Override public String getKeyMap() { return null; }
    @Override public boolean setKeyMap(String name) { return false; }
    @Override public KeyMap<Binding> getKeys() { return null; }
    @Override public ParsedLine getParsedLine() { return null; }
    @Override public String getSearchTerm() { return null; }
    @Override public RegionType getRegionActive() { return null; }
    @Override public int getRegionMark() { return 0; }
    @Override public void addCommandsInBuffer(Collection<String> commands) {}
    @Override public void editAndAddInBuffer(File file) {}
    @Override public String getLastBinding() { return null; }
    @Override public String getTailTip() { return null; }
    @Override public void setTailTip(String tailTip) {}
    @Override public void setAutosuggestion(SuggestionType type) {}
    @Override public SuggestionType getAutosuggestion() { return null; }
}
