package madacode.render;

import madacode.tui.theme.Tk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts markdown text to ANSI-styled terminal output.
 *
 * <p>Handles: code fences (``` and ~~~), headings, bold, italic, inline code,
 * lists, horizontal rules, blockquotes, links, strikethrough, HTML line breaks
 * within table cells (&lt;br&gt;), and width-aware table rendering with fallback.
 */
public class MarkdownRenderer {

    private static final int DEFAULT_RENDER_WIDTH = 100;
    private static final int SAFETY_MARGIN = 2;
    private static final int MAX_ROW_LINES = 4;
    private static final int MIN_COLUMN_WIDTH = 6;
    private static final int MAX_IDEAL_COLUMN_WIDTH = 32;
    private static final int MAX_MIN_COLUMN_WIDTH = 18;

    private final StringBuilder buffer = new StringBuilder();
    private boolean inCodeBlock;
    private String codeBlockLang;
    /** Fence character used to open the current code block ("`" or "~"). */
    private String codeFenceMarker;
    /** Number of fence characters in the opening marker. */
    private int codeFenceLength;

    /** Buffered raw table lines until we can render the whole table block. */
    private final List<String> tableLineBuffer = new ArrayList<>();
    /** Pre-rendered output lines waiting to be drained by renderLine/flushRemaining. */
    private final List<String> pendingOutput = new ArrayList<>();

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern UL = Pattern.compile("^\\s*[•*-]\\s+(.+)$");
    private static final Pattern OL = Pattern.compile("^\\s*\\d+\\.\\s+(.+)$");
    private static final Pattern HR = Pattern.compile("^[-*_]{3,}\\s*$");
    private static final Pattern CODE_FENCE = Pattern.compile("^(```+|~~~+)(.*)$");
    private static final Pattern BQ = Pattern.compile("^>\\s?(.*)$");
    private static final Pattern HTML_BR = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\e\\[[0-9;]*[a-zA-Z]");
    private static final Pattern PARTIAL_FENCE = Pattern.compile("^(```+|~~~+)(.*)$");
    private static final Pattern TABLE_SEPARATOR_CELL = Pattern.compile("^:?-{3,}:?$");
    private static final Pattern BULLET_LINE = Pattern.compile("^([•*-]|\\d+\\.)\\s+(.+)$");
    private static final Set<String> TITLE_HINT_HEADERS = new HashSet<>(Arrays.asList(
            "year", "years", "date", "time", "id", "name", "title", "period",
            "年份", "日期", "时间", "编号", "名称", "标题"
    ));

    /** Feed a chunk of text (may contain partial lines). Strips raw ANSI escapes. */
    public void append(String chunk) {
        if (chunk == null || chunk.isEmpty()) return;
        if (chunk.indexOf('\u001b') >= 0) {
            chunk = ANSI_ESCAPE.matcher(chunk).replaceAll("");
        }
        buffer.append(chunk);
    }

    /** Render a direct raw line using default table width. */
    public String renderLine(String line) {
        return renderLine(line, DEFAULT_RENDER_WIDTH);
    }

    /** Render a direct raw line using the provided table width. */
    public String renderLine(String line, int maxWidth) {
        if (line == null) return null;
        append(line + "\n");
        return renderLine(maxWidth);
    }

    /** Extract and render the next complete line, or null if none. */
    public String renderLine() {
        return renderLine(DEFAULT_RENDER_WIDTH);
    }

    /** Extract and render the next complete line, wrapping tables to maxWidth. */
    public String renderLine(int maxWidth) {
        return renderLine(maxWidth, false);
    }

    /**
     * Extract and render the next complete line.
     *
     * @param holdOpenTable when true, a buffered table is not flushed merely
     *                      because input is temporarily idle. Streaming callers
     *                      use this so a table arriving line-by-line is rendered
     *                      as one complete block.
     */
    public String renderLine(int maxWidth, boolean holdOpenTable) {
        int width = normalizeWidth(maxWidth);
        if (!pendingOutput.isEmpty()) {
            return pendingOutput.remove(0);
        }

        while (true) {
            if (!pendingOutput.isEmpty()) {
                return pendingOutput.remove(0);
            }

            int nl = buffer.indexOf("\n");
            if (nl < 0) {
                if (!holdOpenTable && !tableLineBuffer.isEmpty()) {
                    flushTableBlock(width);
                    if (!pendingOutput.isEmpty()) {
                        return pendingOutput.remove(0);
                    }
                }
                return null;
            }

            String line = buffer.substring(0, nl);
            buffer.delete(0, nl + 1);
            processInputLine(line, width, false);
        }
    }

    /** Render any partial line left in the buffer. */
    public String flushRemaining() {
        return flushRemaining(DEFAULT_RENDER_WIDTH);
    }

    /** Render any partial line left in the buffer, wrapping tables to maxWidth. */
    public String flushRemaining(int maxWidth) {
        int width = normalizeWidth(maxWidth);
        if (!pendingOutput.isEmpty()) {
            return pendingOutput.remove(0);
        }

        if (!buffer.isEmpty()) {
            String line = buffer.toString();
            buffer.setLength(0);
            processInputLine(line, width, true);
        }

        if (!tableLineBuffer.isEmpty()) {
            flushTableBlock(width);
        }

        if (!pendingOutput.isEmpty()) {
            return pendingOutput.remove(0);
        }
        return null;
    }

    // ---- table handling ---------------------------------------------------

    private void processInputLine(String line, int maxWidth, boolean isFinalFlushLine) {
        if (!inCodeBlock && isTableRowCandidate(line)) {
            tableLineBuffer.add(line);
            // If this is the final leftover line, force flush now.
            if (isFinalFlushLine) {
                flushTableBlock(maxWidth);
            }
            return;
        }

        if (!tableLineBuffer.isEmpty()) {
            flushTableBlock(maxWidth);
        }

        String rendered = renderOne(line);
        if (rendered != null) {
            pendingOutput.add(rendered);
        }
    }

    private boolean isTableRowCandidate(String line) {
        if (line == null) return false;
        String trimmed = line.strip();
        if (trimmed.isEmpty()) return false;
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) return false;

        int boxCount = countChar(trimmed, '│');
        if (boxCount >= 2) return true;

        int pipeCount = countChar(trimmed, '|');
        if (pipeCount >= 2) return true;
        return (trimmed.startsWith("|") && trimmed.endsWith("|") && pipeCount >= 1);
    }

    private static int countChar(String s, char ch) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == ch) count++;
        }
        return count;
    }

    private static int countRepeated(String text, int start, char ch) {
        int index = start;
        while (index < text.length() && text.charAt(index) == ch) {
            index++;
        }
        return index - start;
    }

    private void flushTableBlock(int maxWidth) {
        if (tableLineBuffer.isEmpty()) return;

        TableModel model = parseTableModel(tableLineBuffer);
        tableLineBuffer.clear();
        if (model == null) return;

        List<String> horizontal = renderHorizontalTable(model, maxWidth);
        if (horizontal != null) {
            pendingOutput.addAll(horizontal);
            return;
        }

        pendingOutput.addAll(renderVerticalTable(model, maxWidth));
    }

    /**
     * Render the current table buffer as a read-only preview.
     * Does not consume or modify the buffer — safe to call every frame.
     */
    public List<String> previewBufferedTable(int maxWidth) {
        if (tableLineBuffer.isEmpty()) return List.of();
        int width = normalizeWidth(maxWidth);
        TableModel model = parseTableModel(tableLineBuffer);
        if (model == null) return List.of();
        List<String> horizontal = renderHorizontalTable(model, width);
        if (horizontal != null) return horizontal;
        return renderVerticalTable(model, width);
    }

    private TableModel parseTableModel(List<String> rawLines) {
        List<List<String>> rows = new ArrayList<>();
        List<String> alignments = new ArrayList<>();
        boolean separatorSeen = false;
        int separatorRowIndex = -1;

        for (String rawLine : rawLines) {
            char delimiter = detectDelimiter(rawLine);
            List<String> cells = splitTableRow(rawLine, delimiter);
            if (cells.size() < 2) {
                continue;
            }
            if (isSeparatorRow(cells)) {
                if (!separatorSeen) {
                    separatorSeen = true;
                    separatorRowIndex = rows.size();
                    alignments = parseAlignments(cells);
                }
                continue;
            }
            rows.add(cells);
        }

        if (rows.isEmpty()) return null;

        List<String> headerRaw;
        List<List<String>> dataRaw;
        if (separatorSeen && separatorRowIndex > 0) {
            headerRaw = rows.get(0);
            dataRaw = new ArrayList<>();
            for (int i = 1; i < rows.size(); i++) {
                dataRaw.add(rows.get(i));
            }
        } else if (rows.size() >= 2) {
            headerRaw = rows.get(0);
            dataRaw = new ArrayList<>();
            for (int i = 1; i < rows.size(); i++) {
                dataRaw.add(rows.get(i));
            }
        } else {
            headerRaw = rows.get(0);
            dataRaw = List.of();
        }

        int columnCount = headerRaw.size();
        for (List<String> row : dataRaw) {
            columnCount = Math.max(columnCount, row.size());
        }

        List<Cell> headers = toCells(headerRaw, columnCount);
        List<List<Cell>> dataRows = new ArrayList<>(dataRaw.size());
        for (List<String> row : dataRaw) {
            dataRows.add(toCells(row, columnCount));
        }

        List<String> normalizedAlign = new ArrayList<>(columnCount);
        for (int i = 0; i < columnCount; i++) {
            if (i < alignments.size()) normalizedAlign.add(alignments.get(i));
            else normalizedAlign.add("left");
        }

        return new TableModel(headers, dataRows, normalizedAlign, columnCount);
    }

    private char detectDelimiter(String line) {
        return line.indexOf('│') >= 0 ? '│' : '|';
    }

    private List<String> splitTableRow(String line, char delimiter) {
        String trimmed = line.strip();
        if (trimmed.isEmpty()) return List.of();

        if (!trimmed.isEmpty() && trimmed.charAt(0) == delimiter) {
            trimmed = trimmed.substring(1);
        }
        if (!trimmed.isEmpty() && trimmed.charAt(trimmed.length() - 1) == delimiter) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }

        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean escaped = false;
        boolean inCode = false;
        int codeTicks = 0;

        for (int i = 0; i < trimmed.length(); ) {
            char ch = trimmed.charAt(i);

            if (escaped) {
                cell.append(ch);
                escaped = false;
                i++;
                continue;
            }

            if (ch == '\\') {
                cell.append(ch);
                escaped = true;
                i++;
                continue;
            }

            if (ch == '`') {
                int ticks = countRepeated(trimmed, i, '`');
                cell.append(trimmed, i, i + ticks);
                if (!inCode) {
                    if (hasClosingBackticks(trimmed, i + ticks, ticks)) {
                        inCode = true;
                        codeTicks = ticks;
                    }
                } else if (ticks == codeTicks) {
                    inCode = false;
                    codeTicks = 0;
                }
                i += ticks;
                continue;
            }

            if (ch == delimiter && !inCode) {
                cells.add(cell.toString().trim());
                cell.setLength(0);
                i++;
                continue;
            }

            cell.append(ch);
            i++;
        }
        cells.add(cell.toString().trim());
        return cells;
    }

    private boolean hasClosingBackticks(String text, int start, int ticks) {
        for (int i = start; i < text.length(); ) {
            char ch = text.charAt(i);
            if (ch == '\\') {
                i += 2;
                continue;
            }
            if (ch == '`') {
                int foundTicks = countRepeated(text, i, '`');
                if (foundTicks == ticks) {
                    return true;
                }
                i += foundTicks;
                continue;
            }
            i++;
        }
        return false;
    }

    private boolean isSeparatorRow(List<String> cells) {
        if (cells.isEmpty()) return false;
        boolean sawDashes = false;
        for (String cell : cells) {
            String stripped = cell.replace(" ", "");
            if (stripped.isEmpty()) continue;
            if (!TABLE_SEPARATOR_CELL.matcher(stripped).matches()) {
                return false;
            }
            sawDashes = true;
        }
        return sawDashes;
    }

    private List<String> parseAlignments(List<String> separatorCells) {
        List<String> alignments = new ArrayList<>(separatorCells.size());
        for (String cell : separatorCells) {
            String stripped = cell.replace(" ", "");
            if (stripped.startsWith(":") && stripped.endsWith(":")) {
                alignments.add("center");
            } else if (stripped.endsWith(":")) {
                alignments.add("right");
            } else {
                alignments.add("left");
            }
        }
        return alignments;
    }

    private List<Cell> toCells(List<String> row, int columnCount) {
        List<Cell> cells = new ArrayList<>(columnCount);
        for (int i = 0; i < columnCount; i++) {
            String value = i < row.size() ? row.get(i) : "";
            cells.add(parseCell(value));
        }
        return cells;
    }

    private Cell parseCell(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Cell(List.of(InlineMarkdown.Line.empty()));
        }
        String normalized = normalizeHtmlBreaks(raw.trim());
        String[] parts = normalized.split("\\n", -1);
        List<InlineMarkdown.Line> lines = new ArrayList<>(parts.length);
        for (String part : parts) {
            lines.add(InlineMarkdown.parse(part.trim()));
        }
        if (lines.isEmpty()) lines.add(InlineMarkdown.Line.empty());
        return new Cell(lines);
    }

    private List<String> renderHorizontalTable(TableModel model, int maxWidth) {
        int availableWidth = Math.max(20, maxWidth) - SAFETY_MARGIN;
        int borderOverhead = model.columnCount + 1 + model.columnCount * 2;
        int contentWidth = availableWidth - borderOverhead;
        if (contentWidth <= 0) {
            return null;
        }

        ColumnWidths widths = computeColumnWidths(model, contentWidth, availableWidth, borderOverhead);
        if (widths == null) {
            return null;
        }

        // Simulate wrapped row heights before final render.
        for (List<Cell> row : model.rows) {
            int rowHeight = estimateRowHeight(row, widths.widths);
            if (rowHeight > MAX_ROW_LINES) {
                return null;
            }
        }

        List<String> lines = new ArrayList<>();
        lines.add(renderTableBorder(widths.widths, "┌", "┬", "┐"));
        lines.addAll(renderHorizontalRow(model.headers, widths.widths, model.alignments, true));
        lines.add(renderTableBorder(widths.widths, "├", "┼", "┤"));
        for (List<Cell> row : model.rows) {
            lines.addAll(renderHorizontalRow(row, widths.widths, model.alignments, false));
            lines.add(renderTableBorder(widths.widths, "├", "┼", "┤"));
        }
        if (!model.rows.isEmpty()) {
            lines.set(lines.size() - 1, renderTableBorder(widths.widths, "└", "┴", "┘"));
        } else {
            lines.add(renderTableBorder(widths.widths, "└", "┴", "┘"));
        }

        for (String line : lines) {
            if (Tk.displayWidth(line) > maxWidth) {
                return null;
            }
        }

        return lines;
    }

    private String renderTableBorder(int[] widths, String left, String join, String right) {
        StringBuilder sb = new StringBuilder();
        sb.append(Tk.dim(left));
        for (int col = 0; col < widths.length; col++) {
            if (col > 0) {
                sb.append(Tk.dim(join));
            }
            sb.append(Tk.dim("─".repeat(widths[col] + 2)));
        }
        sb.append(Tk.dim(right));
        return sb.toString();
    }

    private ColumnWidths computeColumnWidths(TableModel model,
                                             int contentWidth,
                                             int availableWidth,
                                             int borderOverhead) {
        int n = model.columnCount;
        int[] ideal = new int[n];
        int[] min = new int[n];
        int maxMinCap = Math.max(MIN_COLUMN_WIDTH,
                Math.min(MAX_MIN_COLUMN_WIDTH, Math.max(1, contentWidth / Math.max(1, n))));

        for (int col = 0; col < n; col++) {
            int headerWidth = maxLogicalWidth(model.headers.get(col));
            int maxLineWidth = headerWidth;
            int longestUnbreakable = longestUnbreakableWidth(model.headers.get(col));

            for (List<Cell> row : model.rows) {
                Cell cell = row.get(col);
                maxLineWidth = Math.max(maxLineWidth, maxLogicalWidth(cell));
                longestUnbreakable = Math.max(longestUnbreakable, longestUnbreakableWidth(cell));
            }

            int baseMin = Math.max(headerWidth, longestUnbreakable);
            int minWidth = Math.max(MIN_COLUMN_WIDTH, Math.min(baseMin, maxMinCap));
            int idealWidth = Math.max(minWidth, Math.min(maxLineWidth, MAX_IDEAL_COLUMN_WIDTH));

            min[col] = minWidth;
            ideal[col] = idealWidth;
        }

        int idealTotal = sum(ideal) + borderOverhead;
        if (idealTotal <= availableWidth) {
            return new ColumnWidths(ideal);
        }

        int minTotal = sum(min) + borderOverhead;
        if (minTotal > availableWidth) {
            return null;
        }

        int[] widths = min.clone();
        int extra = availableWidth - borderOverhead - sum(widths);
        if (extra <= 0) {
            return new ColumnWidths(widths);
        }

        int[] overflow = new int[n];
        int overflowTotal = 0;
        for (int i = 0; i < n; i++) {
            overflow[i] = Math.max(0, ideal[i] - min[i]);
            overflowTotal += overflow[i];
        }

        int used = 0;
        if (overflowTotal > 0) {
            for (int i = 0; i < n; i++) {
                int delta = (overflow[i] * extra) / overflowTotal;
                widths[i] += delta;
                used += delta;
            }

            int remain = extra - used;
            while (remain > 0) {
                int pick = -1;
                int bestNeed = -1;
                for (int i = 0; i < n; i++) {
                    int need = ideal[i] - widths[i];
                    if (need > bestNeed) {
                        bestNeed = need;
                        pick = i;
                    }
                }
                if (pick < 0 || bestNeed <= 0) break;
                widths[pick]++;
                remain--;
            }
        } else {
            int i = 0;
            while (used < extra) {
                widths[i % n]++;
                i++;
                used++;
            }
        }

        return new ColumnWidths(widths);
    }

    private int estimateRowHeight(List<Cell> row, int[] widths) {
        int rowHeight = 1;
        for (int i = 0; i < widths.length; i++) {
            List<InlineMarkdown.Line> wrapped = wrapCell(row.get(i), widths[i]);
            rowHeight = Math.max(rowHeight, wrapped.size());
        }
        return rowHeight;
    }

    private List<String> renderHorizontalRow(List<Cell> row,
                                             int[] widths,
                                             List<String> alignments,
                                             boolean header) {
        List<List<InlineMarkdown.Line>> wrappedCells = new ArrayList<>(widths.length);
        int rowHeight = 1;
        for (int i = 0; i < widths.length; i++) {
            List<InlineMarkdown.Line> wrapped = wrapCell(row.get(i), widths[i]);
            wrappedCells.add(wrapped);
            rowHeight = Math.max(rowHeight, wrapped.size());
        }

        List<String> lines = new ArrayList<>(rowHeight);
        for (int lineIndex = 0; lineIndex < rowHeight; lineIndex++) {
            StringBuilder sb = new StringBuilder();
            sb.append(Tk.dim("│")).append(" ");
            for (int col = 0; col < widths.length; col++) {
                if (col > 0) {
                    sb.append(" ").append(Tk.dim("│")).append(" ");
                }
                List<InlineMarkdown.Line> wrapped = wrappedCells.get(col);
                InlineMarkdown.Line segment = lineIndex < wrapped.size()
                        ? wrapped.get(lineIndex)
                        : InlineMarkdown.Line.empty();
                String formatted = InlineMarkdown.render(segment);
                int display = InlineMarkdown.displayWidth(segment);
                String align = alignments.get(col);
                sb.append(padCell(formatted, display, widths[col], align));
            }
            sb.append(" ").append(Tk.dim("│"));
            lines.add(sb.toString());
        }

        return lines;
    }

    private List<String> renderVerticalTable(TableModel model, int maxWidth) {
        List<String> out = new ArrayList<>();
        boolean useTitleColumn = model.columnCount > 1 && preferFirstColumnAsTitle(model.headers.get(0));

        for (int rowIndex = 0; rowIndex < model.rows.size(); rowIndex++) {
            if (rowIndex > 0) out.add("");

            List<Cell> row = model.rows.get(rowIndex);
            String title = useTitleColumn
                    ? firstNonBlank(row.get(0), "Row " + (rowIndex + 1))
                    : "Row " + (rowIndex + 1);
            out.addAll(prefixWrapped(title, "", "", maxWidth));

            int startCol = useTitleColumn ? 1 : 0;
            for (int col = startCol; col < model.columnCount; col++) {
                String key = firstNonBlank(model.headers.get(col), "Column " + (col + 1));
                out.addAll(prefixWrapped(key, "  ", "  ", maxWidth));

                List<InlineMarkdown.Line> valueLines = wrapCell(row.get(col), Math.max(1, maxWidth - 4));
                if (valueLines.isEmpty()) {
                    out.add("    ");
                } else {
                    for (InlineMarkdown.Line valueLine : valueLines) {
                        out.add("    " + InlineMarkdown.render(valueLine));
                    }
                }
            }
        }

        // If the table only had a header row and no data rows, still print it sensibly.
        if (model.rows.isEmpty()) {
            String title = firstNonBlank(model.headers.get(0), "Table");
            out.addAll(prefixWrapped(title, "", "", maxWidth));
            for (int col = 1; col < model.columnCount; col++) {
                String key = firstNonBlank(model.headers.get(col), "Column " + (col + 1));
                out.addAll(prefixWrapped(key, "  ", "  ", maxWidth));
            }
        }

        return out;
    }

    private boolean preferFirstColumnAsTitle(Cell headerCell) {
        String normalized = firstNonBlank(headerCell, "")
                .replaceAll("\\s+", "")
                .toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return false;
        if (TITLE_HINT_HEADERS.contains(normalized)) return true;
        return Tk.displayWidth(normalized) <= 6;
    }

    private List<String> prefixWrapped(String text, String firstPrefix, String nextPrefix, int maxWidth) {
        int firstWidth = Math.max(1, maxWidth - Tk.displayWidth(firstPrefix));
        List<String> wrapped = wrapPlainText(text, firstWidth);
        if (wrapped.isEmpty()) return List.of(firstPrefix);

        List<String> out = new ArrayList<>(wrapped.size());
        out.add(firstPrefix + wrapped.get(0));
        int nextWidth = Math.max(1, maxWidth - Tk.displayWidth(nextPrefix));
        for (int i = 1; i < wrapped.size(); i++) {
            List<String> sub = wrapPlainText(wrapped.get(i), nextWidth);
            if (sub.isEmpty()) {
                out.add(nextPrefix);
            } else {
                for (String piece : sub) {
                    out.add(nextPrefix + piece);
                }
            }
        }
        return out;
    }

    private int normalizeWidth(int maxWidth) {
        if (maxWidth <= 0 || maxWidth >= 2000) {
            return DEFAULT_RENDER_WIDTH;
        }
        return maxWidth;
    }

    private static int sum(int[] values) {
        int s = 0;
        for (int v : values) s += v;
        return s;
    }

    private int maxLogicalWidth(Cell cell) {
        int w = 0;
        for (InlineMarkdown.Line line : cell.logicalLines) {
            w = Math.max(w, InlineMarkdown.displayWidth(line));
        }
        return w;
    }

    private int longestUnbreakableWidth(Cell cell) {
        int longest = 0;
        for (InlineMarkdown.Line logical : cell.logicalLines) {
            longest = Math.max(longest, InlineMarkdown.longestUnbreakableWidth(logical));
        }
        return longest;
    }

    private List<InlineMarkdown.Line> wrapCell(Cell cell, int width) {
        if (width <= 0) return List.of(InlineMarkdown.Line.empty());

        List<InlineMarkdown.Line> out = new ArrayList<>();
        for (InlineMarkdown.Line logical : cell.logicalLines) {
            String line = InlineMarkdown.plainText(logical).trim();
            if (line.isEmpty()) {
                out.add(InlineMarkdown.Line.empty());
                continue;
            }
            Matcher bullet = BULLET_LINE.matcher(line);
            if (bullet.matches()) {
                out.addAll(wrapBulletLine(logical, bullet.group(1), width));
                continue;
            }
            out.addAll(InlineMarkdown.wrap(logical, width));
        }

        if (out.isEmpty()) out.add(InlineMarkdown.Line.empty());
        return out;
    }

    private List<InlineMarkdown.Line> wrapBulletLine(
            InlineMarkdown.Line line,
            String marker,
            int width) {
        String prefix = marker + " ";
        int prefixWidth = Tk.displayWidth(prefix);
        if (width <= prefixWidth) {
            return InlineMarkdown.wrap(line, width);
        }

        InlineMarkdown.Line bodyLine = InlineMarkdown.dropLeadingText(line, prefix.length());
        List<InlineMarkdown.Line> bodyLines = InlineMarkdown.wrap(bodyLine, Math.max(1, width - prefixWidth));
        if (bodyLines.isEmpty()) {
            return List.of(InlineMarkdown.parse(prefix.trim()));
        }

        List<InlineMarkdown.Line> out = new ArrayList<>(bodyLines.size());
        out.add(prependPlain(prefix, bodyLines.get(0)));
        String continuationPrefix = " ".repeat(Math.max(0, prefix.length()));
        for (int i = 1; i < bodyLines.size(); i++) {
            out.add(prependPlain(continuationPrefix, bodyLines.get(i)));
        }
        return out;
    }

    private InlineMarkdown.Line prependPlain(String prefix, InlineMarkdown.Line line) {
        List<InlineMarkdown.Run> runs = new ArrayList<>();
        runs.add(new InlineMarkdown.Run(prefix, InlineMarkdown.Style.NORMAL));
        runs.addAll(line.runs());
        return new InlineMarkdown.Line(runs);
    }

    private List<String> wrapPlainText(String text, int width) {
        if (text == null || text.isEmpty()) return List.of("");
        if (width <= 0) return List.of(text);
        List<String> lines = wordWrap(text, width);
        return lines.isEmpty() ? List.of("") : lines;
    }

    private String padCell(String content, int displayW, int targetW, String align) {
        int pad = Math.max(0, targetW - displayW);
        if ("right".equals(align)) return " ".repeat(pad) + content;
        if ("center".equals(align)) {
            int l = pad / 2;
            return " ".repeat(l) + content + " ".repeat(pad - l);
        }
        return content + " ".repeat(pad);
    }

    private String firstNonBlank(Cell cell, String fallback) {
        for (InlineMarkdown.Line line : cell.logicalLines) {
            String text = InlineMarkdown.plainText(line);
            if (text != null && !text.isBlank()) return text.trim();
        }
        return fallback;
    }

    // ---- per-line rendering (non-table) -----------------------------------

    private String renderOne(String line) {
        Matcher cf = CODE_FENCE.matcher(line);
        if (cf.matches()) {
            String marker = cf.group(1);
            String info = cf.group(2).trim();
            String lang = info.isEmpty() ? "" : info.split("\\s+", 2)[0];

            if (!inCodeBlock) {
                // Opening fence
                inCodeBlock = true;
                codeBlockLang = lang;
                codeFenceMarker = marker.startsWith("`") ? "`" : "~";
                codeFenceLength = marker.length();
                String langTag = lang.isEmpty() ? "" : " " + lang;
                return Tk.codeFence("┌" + "─".repeat(2) + langTag);
            } else {
                // Possible closing fence — check same type and sufficient length
                boolean sameType = (marker.startsWith("`") && "`".equals(codeFenceMarker))
                        || (marker.startsWith("~") && "~".equals(codeFenceMarker));
                if (sameType && marker.length() >= codeFenceLength && info.isEmpty()) {
                    inCodeBlock = false;
                    codeBlockLang = null;
                    codeFenceMarker = null;
                    codeFenceLength = 0;
                    return Tk.codeFence("└" + "─".repeat(2));
                }
                // Not a valid closing fence — render as code block content
                return renderCodeLine(line);
            }
        }

        if (inCodeBlock) {
            return renderCodeLine(line);
        }

        if (line.isBlank()) return "";

        Matcher hr = HR.matcher(line);
        if (hr.matches()) {
            return Tk.dim("─".repeat(Math.min(line.length(), 60)));
        }

        Matcher h = HEADING.matcher(line);
        if (h.matches()) {
            return Tk.heading(inlineFormat(h.group(2)));
        }

        Matcher ul = UL.matcher(line);
        if (ul.matches()) {
            return indentPrefix(line) + Tk.dim("•") + " " + inlineFormat(ul.group(1));
        }

        Matcher ol = OL.matcher(line);
        if (ol.matches()) {
            String num = ol.group().trim().split("\\.", 2)[0];
            return indentPrefix(line) + Tk.dim(num + ".") + " " + inlineFormat(ol.group(1));
        }

        Matcher bq = BQ.matcher(line);
        if (bq.matches()) {
            return Tk.codeFence("│") + " " + Tk.quote(inlineFormat(bq.group(1)));
        }

        return inlineFormat(line);
    }

    /** Render a line inside a code block. */
    private String renderCodeLine(String line) {
        String highlighted = "diff".equalsIgnoreCase(codeBlockLang)
                ? DiffHighlighter.highlightAndRender(line, 1).stream().findFirst().orElse(line)
                : CodeHighlighter.highlight(codeBlockLang, line);
        return " " + Tk.codeFence("│") + " " + highlighted;
    }

    /** Leading whitespace prefix for indented lists. */
    private static String indentPrefix(String line) {
        int leading = 0;
        while (leading < line.length() && line.charAt(leading) == ' ') leading++;
        if (leading < 2) return "";
        return " ".repeat(leading);
    }

    // ---- inline formatting -------------------------------------------

    /**
     * Apply bold/italic/code/strike/link formatting. Inline code
     * spans are protected from other patterns (bold inside backticks
     * is rendered as code, not bold).
     */
    private String inlineFormat(String text) {
        return InlineMarkdown.render(InlineMarkdown.parse(text));
    }

    // ---- HTML-lite helpers -------------------------------------------

    /**
     * Convert HTML line breaks ({@code <br>}, {@code <br/>}, {@code <br />})
     * to {@code \n}. Case-insensitive.
     */
    static String normalizeHtmlBreaks(String text) {
        if (text == null || text.isEmpty()) return text;
        return HTML_BR.matcher(text).replaceAll("\n");
    }

    /**
     * Split text on HTML/soft line breaks after normalizing them.
     * Returns trimmed segments; never returns an empty list.
     */
    static List<String> splitSoftBreaks(String text) {
        if (text == null || text.isEmpty()) return List.of("");
        String normalized = normalizeHtmlBreaks(text);
        String[] parts = normalized.split("\n", -1);
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            result.add(part.trim());
        }
        return result;
    }

    // ---- wrap helpers ---------------------------------------------------

    /**
     * Word-wrap plain text at {@code maxWidth} (terminal columns).
     * Returns a list of lines, each ≤ maxWidth display columns.
     */
    static List<String> wordWrap(String text, int maxWidth) {
        if (text == null) return List.of("");
        if (maxWidth <= 0 || text.isEmpty()) return List.of(text);

        List<String> result = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int lineW = 0;

        String[] words = text.trim().split("\\s+");
        for (String word : words) {
            if (word.isEmpty()) continue;
            int wordW = Tk.displayWidth(word);

            if (wordW > maxWidth) {
                if (lineW > 0) {
                    result.add(line.toString());
                    line.setLength(0);
                    lineW = 0;
                }
                List<String> hard = hardWrap(word, maxWidth);
                for (int i = 0; i < hard.size(); i++) {
                    String part = hard.get(i);
                    int partW = Tk.displayWidth(part);
                    if (partW == maxWidth || i < hard.size() - 1) {
                        result.add(part);
                    } else {
                        line.append(part);
                        lineW = partW;
                    }
                }
                continue;
            }

            if (lineW == 0) {
                line.append(word);
                lineW = wordW;
            } else if (lineW + 1 + wordW <= maxWidth) {
                line.append(' ').append(word);
                lineW += 1 + wordW;
            } else {
                result.add(line.toString());
                line.setLength(0);
                line.append(word);
                lineW = wordW;
            }
        }

        if (lineW > 0) result.add(line.toString());
        if (result.isEmpty()) result.add("");
        return result;
    }

    private static List<String> hardWrap(String text, int maxWidth) {
        if (text == null || text.isEmpty()) return List.of("");

        List<String> result = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int width = 0;
        for (int offset = 0; offset < text.length(); ) {
            int end = madacode.tui.TerminalText.clusterEnd(text, offset);
            String cluster = text.substring(offset, end);
            int chWidth = Math.max(1, Tk.displayWidth(cluster));
            if (width > 0 && width + chWidth > maxWidth) {
                result.add(line.toString());
                line = new StringBuilder();
                width = 0;
            }
            line.append(cluster);
            width += chWidth;
            offset = end;
        }
        if (!line.isEmpty()) result.add(line.toString());
        if (result.isEmpty()) result.add("");
        return result;
    }

    // ---- partial-line rendering (read-only, no state mutation) -----------

    /**
     * Best-effort rendering of a partial line (no trailing newline yet).
     * Pure read — does not modify renderer state.
     */
    public String renderPartial(String partial) {
        return renderPartial(partial, Integer.MAX_VALUE);
    }

    /**
     * Renders a partial line, wrapped at {@code maxWidth} display columns.
     * Returns only the first wrapped segment; use
     * {@link #renderPartialLines(String, int)} for full multi-line output.
     */
    public String renderPartial(String partial, int maxWidth) {
        List<String> lines = renderPartialLines(partial, maxWidth);
        return lines.isEmpty() ? "" : lines.get(0);
    }

    /**
     * Render a partial line into potentially multiple terminal lines.
     * Returns all wrapped segments so callers (like live preview) can
     * display the full partial content rather than a single truncated line.
     *
     * <p>Pure read — does not modify renderer state.
     *
     * @param partial  the unterminated text to render
     * @param maxWidth max terminal columns per line (≤ 0 = no wrapping)
     * @return list of rendered lines, never null
     */
    public List<String> renderPartialLines(String partial, int maxWidth) {
        if (partial == null || partial.isEmpty()) return List.of();

        List<String> wrapped;
        if (maxWidth > 0 && maxWidth < 2000) {
            wrapped = wordWrap(partial, maxWidth);
        } else {
            wrapped = List.of(partial);
        }

        List<String> result = new ArrayList<>(wrapped.size());
        for (String segment : wrapped) {
            result.add(renderPartialSegment(segment));
        }
        return result;
    }

    /** Render a single partial segment (already wrapped to fit maxWidth). */
    private String renderPartialSegment(String segment) {
        Matcher pf = PARTIAL_FENCE.matcher(segment);
        if (!inCodeBlock && pf.matches()) {
            String info = pf.group(2).trim();
            String lang = info.isEmpty() ? "" : info.split("\\s+", 2)[0];
            String langTag = lang.isEmpty() ? "" : " " + lang;
            return Tk.codeFence("┌" + "─".repeat(2) + langTag);
        }

        if (inCodeBlock) {
            String highlighted = "diff".equalsIgnoreCase(codeBlockLang)
                    ? DiffHighlighter.highlightAndRender(segment, 1).stream().findFirst().orElse(segment)
                    : CodeHighlighter.highlight(codeBlockLang, segment);
            return " " + Tk.codeFence("│") + " " + highlighted;
        }

        if (segment.isBlank()) return segment;

        Matcher h = HEADING.matcher(segment);
        if (h.matches()) {
            return Tk.heading(inlineFormat(h.group(2)));
        }

        Matcher ul = UL.matcher(segment);
        if (ul.matches()) {
            return indentPrefix(segment) + Tk.dim("•") + " " + inlineFormat(ul.group(1));
        }

        Matcher ol = OL.matcher(segment);
        if (ol.matches()) {
            String num = ol.group().trim().split("\\.", 2)[0];
            return indentPrefix(segment) + Tk.dim(num + ".") + " " + inlineFormat(ol.group(1));
        }

        Matcher bq = BQ.matcher(segment);
        if (bq.matches()) {
            return Tk.codeFence("│") + " " + Tk.quote(inlineFormat(bq.group(1)));
        }

        return inlineFormat(segment);
    }

    public boolean isInCodeBlock() {
        return inCodeBlock;
    }

    /** Reset state for a fresh render. */
    public void reset() {
        buffer.setLength(0);
        inCodeBlock = false;
        codeBlockLang = null;
        codeFenceMarker = null;
        codeFenceLength = 0;
        tableLineBuffer.clear();
        pendingOutput.clear();
    }

    private static final class TableModel {
        final List<Cell> headers;
        final List<List<Cell>> rows;
        final List<String> alignments;
        final int columnCount;

        TableModel(List<Cell> headers, List<List<Cell>> rows, List<String> alignments, int columnCount) {
            this.headers = headers;
            this.rows = rows;
            this.alignments = alignments;
            this.columnCount = columnCount;
        }
    }

    private static final class Cell {
        final List<InlineMarkdown.Line> logicalLines;

        Cell(List<InlineMarkdown.Line> logicalLines) {
            this.logicalLines = logicalLines;
        }
    }

    private static final class ColumnWidths {
        final int[] widths;

        ColumnWidths(int[] widths) {
            this.widths = widths;
        }
    }
}
