package madacode.render;

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.Node;
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Converts markdown text to ANSI-styled terminal output.
 *
 * <p>Internally uses commonmark-java for parsing and {@link AnsiMarkdownWriter}
 * for ANSI rendering. The public API is unchanged.
 */
public class MarkdownRenderer {

    private static final int DEFAULT_RENDER_WIDTH = 100;
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\e\\[[0-9;]*[a-zA-Z]");
    private static final Pattern HTML_BR = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);

    private final Parser parser = Parser.builder()
            .extensions(List.of(TablesExtension.create(), StrikethroughExtension.create(), TaskListItemsExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();
    private final AnsiMarkdownWriter writer = new AnsiMarkdownWriter();

    private final StringBuilder source = new StringBuilder();
    private final ArrayDeque<String> outputQueue = new ArrayDeque<>();
    private boolean pendingBlockSeparator;
    private boolean inCodeBlock;
    private String codeBlockLang = "";
    private char codeFenceChar;
    private int codeFenceLen;

    public void append(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        if (chunk.indexOf('\u001b') >= 0) {
            chunk = ANSI_ESCAPE.matcher(chunk).replaceAll("");
        }
        source.append(chunk);
    }

    public String renderLine(String line) {
        return renderLine(line, DEFAULT_RENDER_WIDTH);
    }

    public String renderLine(String line, int maxWidth) {
        if (line == null) return null;
        append(line + "\n");
        return renderLine(maxWidth);
    }

    public String renderLine() {
        return renderLine(DEFAULT_RENDER_WIDTH);
    }

    public String renderLine(int maxWidth) {
        return renderLine(maxWidth, false);
    }

    public String renderLine(int maxWidth, boolean holdOpenTable) {
        int width = normalizeWidth(maxWidth);
        if (!outputQueue.isEmpty()) {
            return outputQueue.pollFirst();
        }
        if (source.isEmpty()) return null;
        if (!hasCompleteLine()) return null;
        boolean flushAll;
        if (!holdOpenTable) {
            flushAll = true;
        } else if (source.charAt(source.length() - 1) != '\n') {
            flushAll = false;
        } else if (source.toString().endsWith("\n\n")) {
            // Blank line after content - flush everything
            flushAll = true;
        } else {
            flushAll = !looksLikeTable();
        }
        commit(width, flushAll);
        return outputQueue.isEmpty() ? null : outputQueue.pollFirst();
    }

    private boolean looksLikeTable() {
        String src = source.toString();
        // Check if the last non-empty line looks like a table row
        String[] lines = src.split("\\R", -1);
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].strip();
            if (!line.isEmpty()) {
                int pipeCount = 0;
                for (int j = 0; j < line.length(); j++) {
                    char c = line.charAt(j);
                    if (c == '|' || c == '│') pipeCount++;
                }
                return pipeCount >= 2;
            }
        }
        return false;
    }

    public String flushRemaining() {
        return flushRemaining(DEFAULT_RENDER_WIDTH);
    }

    public String flushRemaining(int maxWidth) {
        int width = normalizeWidth(maxWidth);
        if (!outputQueue.isEmpty()) return outputQueue.pollFirst();
        if (!source.isEmpty()) commit(width, true);
        return outputQueue.isEmpty() ? null : outputQueue.pollFirst();
    }

    // ---- commit algorithm ---------------------------------------------------

    private void commit(int width, boolean flushAll) {
        if (source.isEmpty() && !inCodeBlock) return;

        String srcText = source.toString();
        
        if (inCodeBlock) {
            renderCodeBlockLines(srcText, width, flushAll);
            return;
        }
        
        String preprocessed = preprocessSource(srcText);
        Node doc;
        try {
            doc = parser.parse(preprocessed);
        } catch (Exception e) {
            for (String line : srcText.split("\\R", -1)) {
                if (!line.isEmpty()) outputQueue.addLast(line);
            }
            source.setLength(0);
            return;
        }

        List<Node> blocks = new ArrayList<>();
        for (Node child = doc.getFirstChild(); child != null; child = child.getNext()) {
            blocks.add(child);
        }

        if (blocks.isEmpty()) {
            source.setLength(0);
            return;
        }

        int keep = flushAll ? 0 : 1;
        int commitEnd = blocks.size() - keep;
        if (commitEnd <= 0) {
            updateFenceStateFromSource(srcText);
            return;
        }

        boolean firstCommitted = true;
        for (int i = 0; i < commitEnd; i++) {
            Node block = blocks.get(i);
            List<String> rendered = writer.render(block, width);
            if (!rendered.isEmpty()) {
                if (!firstCommitted || pendingBlockSeparator) outputQueue.addLast("");
                outputQueue.addAll(rendered);
                pendingBlockSeparator = false;
                firstCommitted = false;
            }
        }

        boolean committedThroughEnd = !(keep > 0 && commitEnd < blocks.size());
        if (committedThroughEnd && sourceEndsWithBlankLine(srcText)) {
            pendingBlockSeparator = true;
        }

        if (keep > 0 && commitEnd < blocks.size()) {
            Node keptBlock = blocks.get(commitEnd);
            int trimLineIndex = sourceLineIndex(keptBlock);
            if (trimLineIndex >= 0) {
                int charOffset = lineIndexToCharOffset(srcText, trimLineIndex);
                if (charOffset >= 0 && charOffset < source.length()) {
                    source.delete(0, charOffset);
                } else {
                    source.setLength(0);
                }
            } else {
                source.setLength(0);
            }
        } else {
            source.setLength(0);
        }

        updateFenceStateFromSource(source.toString());
        if (source.isEmpty() && commitEnd > 0) {
            Node lastCommitted = blocks.get(commitEnd - 1);
            if (lastCommitted instanceof org.commonmark.node.FencedCodeBlock fcb) {
                Integer closingLen = fcb.getClosingFenceLength();
                if (closingLen == null || closingLen == 0) {
                    inCodeBlock = true;
                    String info = fcb.getInfo();
                    codeBlockLang = (info != null && !info.isEmpty()) ? info.split("\\s+", 2)[0] : "";
                    codeFenceChar = fcb.getFenceChar();
                    codeFenceLen = fcb.getFenceLength();
                }
            }
        }
    }

    private void renderCodeBlockLines(String srcText, int width, boolean flushAll) {
        String[] lines = srcText.split("\\R", -1);
        List<String> codeLines = new ArrayList<>();
        boolean foundClosing = false;
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && trimmed.charAt(0) == codeFenceChar) {
                boolean allFence = true;
                for (int j = 0; j < trimmed.length(); j++) {
                    if (trimmed.charAt(j) != codeFenceChar) { allFence = false; break; }
                }
                if (allFence && trimmed.length() >= codeFenceLen) {
                    foundClosing = true;
                    break;
                }
            }
            if (i < lines.length - 1 || !line.isEmpty()) {
                codeLines.add(line);
            }
        }
        
        if (!flushAll && !foundClosing && !codeLines.isEmpty()) {
            int keep = 1;
            int commitEnd = codeLines.size() - keep;
            if (commitEnd <= 0) return;

            for (int i = 0; i < commitEnd; i++) {
                outputQueue.addLast(AnsiMarkdownWriter.codeLine(codeLines.get(i), codeBlockLang));
            }

            StringBuilder remaining = new StringBuilder();
            for (int i = commitEnd; i < codeLines.size(); i++) {
                if (i > commitEnd) remaining.append("\n");
                remaining.append(codeLines.get(i));
            }
            source.setLength(0);
            source.append(remaining);
        } else {
            for (String cl : codeLines) {
                outputQueue.addLast(AnsiMarkdownWriter.codeLine(cl, codeBlockLang));
            }

            if (foundClosing) {
                outputQueue.addLast(AnsiMarkdownWriter.codeFenceBottom());
                inCodeBlock = false;
                codeBlockLang = "";
                codeFenceChar = 0;
                codeFenceLen = 0;
            }
            
            source.setLength(0);
        }
    }

    private void updateFenceStateFromSource(String src) {
        inCodeBlock = false;
        codeBlockLang = "";
        codeFenceChar = 0;
        codeFenceLen = 0;
        int i = 0;
        while (i < src.length()) {
            int lineEnd = src.indexOf('\n', i);
            if (lineEnd < 0) lineEnd = src.length();
            String line = src.substring(i, lineEnd);
            String trimmed = line.stripLeading();

            if (!inCodeBlock) {
                if (trimmed.length() >= 3 && (trimmed.charAt(0) == '`' || trimmed.charAt(0) == '~')) {
                    char c = trimmed.charAt(0);
                    int count = 0;
                    while (count < trimmed.length() && trimmed.charAt(count) == c) count++;
                    if (count >= 3) {
                        inCodeBlock = true;
                        codeFenceChar = c;
                        codeFenceLen = count;
                        String info = trimmed.substring(count).trim();
                        codeBlockLang = info.isEmpty() ? "" : info.split("\\s+", 2)[0];
                    }
                }
            } else {
                String tc = trimmed.stripTrailing();
                if (!tc.isEmpty() && tc.charAt(0) == codeFenceChar) {
                    boolean allFence = true;
                    for (int j = 0; j < tc.length(); j++) {
                        if (tc.charAt(j) != codeFenceChar) { allFence = false; break; }
                    }
                    if (allFence && tc.length() >= codeFenceLen) {
                        inCodeBlock = false;
                        codeBlockLang = "";
                        codeFenceChar = 0;
                        codeFenceLen = 0;
                    }
                }
            }
            i = lineEnd < src.length() ? lineEnd + 1 : src.length();
        }
    }

    private static int sourceLineIndex(Node node) {
        List<SourceSpan> spans = node.getSourceSpans();
        if (spans != null && !spans.isEmpty()) return spans.get(0).getLineIndex();
        return -1;
    }

    private static int lineIndexToCharOffset(String text, int lineIndex) {
        if (lineIndex <= 0) return 0;
        int currentLine = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                currentLine++;
                if (currentLine == lineIndex) return i + 1;
            }
        }
        return text.length();
    }

    // ---- source preprocessing -----------------------------------------------

    private static String preprocessSource(String src) {
        if (src.indexOf('│') < 0) return src;
        StringBuilder sb = new StringBuilder(src.length());
        boolean inCode = false;
        char fenceChar = 0;
        int fenceLen = 0;
        int i = 0;
        while (i < src.length()) {
            int lineStart = i;
            int lineEnd = src.indexOf('\n', i);
            if (lineEnd < 0) lineEnd = src.length();
            String line = src.substring(lineStart, lineEnd);
            String trimmed = line.stripLeading();

            if (!inCode && (trimmed.startsWith("```") || trimmed.startsWith("~~~"))) {
                char fc = trimmed.charAt(0);
                int fl = 0;
                while (fl < trimmed.length() && trimmed.charAt(fl) == fc) fl++;
                if (fl >= 3) {
                    inCode = true;
                    fenceChar = fc;
                    fenceLen = fl;
                }
            } else if (inCode) {
                String tc = trimmed.stripTrailing();
                if (tc.length() >= fenceLen && tc.charAt(0) == fenceChar) {
                    boolean allFence = true;
                    for (int j = 0; j < tc.length(); j++) {
                        if (tc.charAt(j) != fenceChar) { allFence = false; break; }
                    }
                    if (allFence) {
                        inCode = false;
                        fenceChar = 0;
                        fenceLen = 0;
                    }
                }
            }

            if (!inCode) {
                sb.append(line.replace('│', '|'));
            } else {
                sb.append(line);
            }
            if (lineEnd < src.length()) {
                sb.append('\n');
                i = lineEnd + 1;
            } else {
                i = lineEnd;
            }
        }
        return sb.toString();
    }

    // ---- table preview (pure function) --------------------------------------

    public List<String> previewBufferedTable(int maxWidth) {
        if (source.isEmpty()) return List.of();
        int width = normalizeWidth(maxWidth);
        String preprocessed = preprocessSource(source.toString());
        Node doc;
        try {
            doc = parser.parse(preprocessed);
        } catch (Exception e) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        boolean firstBlock = true;
        for (Node child = doc.getFirstChild(); child != null; child = child.getNext()) {
            List<String> rendered = writer.render(child, width);
            if (!rendered.isEmpty()) {
                if (!firstBlock) result.add("");
                result.addAll(rendered);
                firstBlock = false;
            }
        }
        return result;
    }

    // ---- code block tracking ------------------------------------------------

    public boolean isInCodeBlock() {
        return inCodeBlock;
    }

    private String currentCodeBlockLang() {
        return inCodeBlock ? codeBlockLang : "";
    }

    // ---- partial-line rendering (pure) --------------------------------------

    public String renderPartial(String partial) {
        return renderPartial(partial, Integer.MAX_VALUE);
    }

    public String renderPartial(String partial, int maxWidth) {
        List<String> lines = renderPartialLines(partial, maxWidth);
        return lines.isEmpty() ? "" : lines.getFirst();
    }

    public List<String> renderPartialLines(String partial, int maxWidth) {
        if (partial == null || partial.isEmpty()) return List.of();

        if (isInCodeBlock()) {
            String lang = currentCodeBlockLang();
            List<String> result = new ArrayList<>();
            for (String line : partial.split("\\R", -1)) {
                result.add(AnsiMarkdownWriter.codeLine(line, lang));
            }
            return result;
        }

        int width = (maxWidth > 0 && maxWidth < 2000) ? maxWidth : Integer.MAX_VALUE;

        if (isPartialFence(partial)) {
            String info = partial.replaceFirst("^[`~]+", "").trim();
            String lang = info.isEmpty() ? "" : info.split("\\s+", 2)[0];
            return List.of(AnsiMarkdownWriter.codeFenceTop(lang));
        }

        try {
            Node doc = parser.parse(partial);
            List<String> result = new ArrayList<>();
            for (Node child = doc.getFirstChild(); child != null; child = child.getNext()) {
                result.addAll(writer.render(child, width));
            }
            if (result.isEmpty()) result.add(partial);
            return result;
        } catch (Exception e) {
            if (width > 0 && width < 2000) return AnsiMarkdownWriter.wordWrap(partial, width);
            return List.of(partial);
        }
    }

    private static boolean isPartialFence(String text) {
        if (text == null || text.length() < 3) return false;
        char first = text.charAt(0);
        if (first != '`' && first != '~') return false;
        int count = 0;
        while (count < text.length() && text.charAt(count) == first) count++;
        return count >= 3;
    }

    public void reset() {
        source.setLength(0);
        outputQueue.clear();
        pendingBlockSeparator = false;
        inCodeBlock = false;
        codeBlockLang = "";
        codeFenceChar = 0;
        codeFenceLen = 0;
    }

    // ---- utilities ----------------------------------------------------------

    private int normalizeWidth(int maxWidth) {
        if (maxWidth <= 0 || maxWidth >= 2000) return DEFAULT_RENDER_WIDTH;
        return maxWidth;
    }

    private boolean hasCompleteLine() {
        return source.indexOf("\n") >= 0;
    }

    private static boolean sourceEndsWithBlankLine(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.endsWith("\n\n") || text.matches("(?s).*\\R[ \\t]*\\R$");
    }

    // ---- HTML-lite helpers (backward compat) --------------------------------

    static String normalizeHtmlBreaks(String text) {
        if (text == null || text.isEmpty()) return text;
        return HTML_BR.matcher(text).replaceAll("\n");
    }

    static List<String> splitSoftBreaks(String text) {
        if (text == null || text.isEmpty()) return List.of("");
        String normalized = normalizeHtmlBreaks(text);
        String[] parts = normalized.split("\n", -1);
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) result.add(part.trim());
        return result;
    }

    static List<String> wordWrap(String text, int maxWidth) {
        return AnsiMarkdownWriter.wordWrap(text, maxWidth);
    }
}
