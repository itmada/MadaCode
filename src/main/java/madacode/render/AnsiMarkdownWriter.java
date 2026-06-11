package madacode.render;

import madacode.tui.TerminalText;
import madacode.tui.theme.Tk;

import org.commonmark.node.BlockQuote;
import org.commonmark.node.BulletList;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.HtmlBlock;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.OrderedList;
import org.commonmark.node.Paragraph;
import org.commonmark.node.ThematicBreak;

import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableBody;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TableHead;
import org.commonmark.ext.gfm.tables.TableRow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Renders a commonmark AST node into ANSI-styled terminal output lines.
 * Package-private — used only by {@link MarkdownRenderer}.
 */
final class AnsiMarkdownWriter {

    private static final int SAFETY_MARGIN = 2;
    private static final int MAX_ROW_LINES = 4;
    private static final int MIN_COLUMN_WIDTH = 6;
    private static final int MAX_IDEAL_COLUMN_WIDTH = 32;
    private static final int MAX_MIN_COLUMN_WIDTH = 18;
    private static final Set<String> TITLE_HINT_HEADERS = new HashSet<>(Arrays.asList(
            "year", "years", "date", "time", "id", "name", "title", "period",
            "年份", "日期", "时间", "编号", "名称", "标题"
    ));

    List<String> render(Node block, int maxWidth) {
        List<String> out = new ArrayList<>();
        renderBlock(block, maxWidth, 0, out);
        return out;
    }

    // ---- block-level visitor ------------------------------------------------

    private void renderBlock(Node node, int maxWidth, int indent, List<String> out) {
        if (node instanceof Heading h) {
            InlineMarkdown.Line line = collectLine(h);
            String rendered = Tk.heading(InlineMarkdown.render(line));
            if (indent > 0) rendered = " ".repeat(indent) + rendered;
            out.add(rendered);
            return;
        }

        if (node instanceof Paragraph p) {
            renderParagraph(p, maxWidth, indent, out);
            return;
        }

        if (node instanceof BulletList bl) {
            renderBulletList(bl, maxWidth, indent, out);
            return;
        }

        if (node instanceof OrderedList ol) {
            renderOrderedList(ol, maxWidth, indent, out);
            return;
        }

        if (node instanceof ListItem li) {
            renderListItem(li, maxWidth, indent, out);
            return;
        }

        if (node instanceof BlockQuote bq) {
            renderBlockquote(bq, maxWidth, indent, out);
            return;
        }

        if (node instanceof FencedCodeBlock cb) {
            renderFencedCodeBlock(cb, maxWidth, indent, out);
            return;
        }

        if (node instanceof IndentedCodeBlock cb) {
            renderIndentedCodeBlock(cb, maxWidth, indent, out);
            return;
        }

        if (node instanceof ThematicBreak) {
            int len = Math.min(maxWidth, 60);
            String line = Tk.dim("─".repeat(Math.max(1, len)));
            if (indent > 0) line = " ".repeat(indent) + line;
            out.add(line);
            return;
        }

        if (node instanceof HtmlBlock html) {
            String literal = html.getLiteral();
            if (literal != null && !literal.isEmpty()) {
                for (String l : literal.split("\\R", -1)) {
                    String rendered = Tk.dim(l);
                    if (indent > 0) rendered = " ".repeat(indent) + rendered;
                    out.add(rendered);
                }
            }
            return;
        }

        if (node instanceof TableBlock tb) {
            renderTableBlock(tb, maxWidth, indent, out);
            return;
        }

        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            renderBlock(child, maxWidth, indent, out);
        }
    }

    // ---- Paragraph ----------------------------------------------------------

    private void renderParagraph(Paragraph p, int maxWidth, int indent, List<String> out) {
        InlineMarkdown.Line line = collectLine(p);
        if (line == null || InlineMarkdown.plainText(line).isBlank()) return;
        int avail = Math.max(1, maxWidth - indent);
        List<InlineMarkdown.Line> wrapped = InlineMarkdown.wrap(line, avail);
        for (InlineMarkdown.Line wl : wrapped) {
            String rendered = InlineMarkdown.render(wl);
            if (indent > 0) rendered = " ".repeat(indent) + rendered;
            out.add(rendered);
        }
    }

    // ---- Lists --------------------------------------------------------------

    /**
     * Whether a list renders "loose" (blank line between items). This is an
     * authoritative property the parser computes from blank-line structure
     * ({@link ListBlock#isTight()}); the renderer must not re-infer it from
     * node shape — tight list items still wrap their content in paragraphs.
     */
    private static boolean isLoose(ListBlock list) {
        return !list.isTight();
    }

    private void renderBulletList(BulletList bl, int maxWidth, int indent, List<String> out) {
        boolean loose = isLoose(bl);
        boolean first = true;
        for (Node child = bl.getFirstChild(); child != null; child = child.getNext()) {
            if (!(child instanceof ListItem li)) continue;
            if (!first && loose) out.add("");
            first = false;
            renderListItemContent(li, maxWidth, indent, Tk.dim("•"), "  ", out, loose);
        }
    }

    private void renderOrderedList(OrderedList ol, int maxWidth, int indent, List<String> out) {
        boolean loose = isLoose(ol);
        boolean first = true;
        int counter = ol.getStartNumber();
        for (Node child = ol.getFirstChild(); child != null; child = child.getNext()) {
            if (!(child instanceof ListItem li)) continue;
            if (!first && loose) out.add("");
            first = false;
            String numStr = String.valueOf(counter++);
            String marker = Tk.dim(numStr + ".");
            String contPadding = " ".repeat(numStr.length() + 1);
            renderListItemContent(li, maxWidth, indent, marker, contPadding, out, loose);
        }
    }

    private void renderListItem(ListItem li, int maxWidth, int indent, List<String> out) {
        // A list item's looseness is a property of its parent list.
        boolean loose = li.getParent() instanceof ListBlock parent && isLoose(parent);
        renderListItemContent(li, maxWidth, indent, Tk.dim("•"), "  ", out, loose);
    }

    private void renderListItemContent(ListItem li, int maxWidth, int indent,
                                        String marker, String contPadding,
                                        List<String> out, boolean loose) {
        // Collect non-task children
        List<Node> children = collectChildrenSkippingTaskMarker(li);

        boolean isTask = false;
        boolean taskChecked = false;
        if (li.getFirstChild() instanceof TaskListItemMarker tlm) {
            isTask = true;
            taskChecked = tlm.isChecked();
        }
        String taskPrefix = isTask ? (taskChecked ? Tk.success("✓") + " " : Tk.dim("✗") + " ") : "";

        if (children.isEmpty()) {
            out.add(" ".repeat(indent) + marker + " " + taskPrefix);
            return;
        }

        boolean firstChild = true;
        for (Node child : children) {
            if (child instanceof Paragraph p) {
                InlineMarkdown.Line line = collectLine(p);
                if (line == null || InlineMarkdown.plainText(line).isBlank()) {
                    if (loose && !firstChild) out.add("");
                    continue;
                }
                String prefix = marker + " " + taskPrefix;
                int bodyAvail = Math.max(1, maxWidth - indent - Tk.displayWidth(prefix) - 1);
                List<InlineMarkdown.Line> wrapped = InlineMarkdown.wrap(line, Math.max(1, bodyAvail));
                if (wrapped.isEmpty()) {
                    out.add(" ".repeat(indent) + prefix);
                } else {
                    for (int i = 0; i < wrapped.size(); i++) {
                        String pfx = i == 0 ? prefix : contPadding;
                        String rendered = InlineMarkdown.render(wrapped.get(i));
                        out.add(" ".repeat(indent) + pfx + rendered);
                    }
                }
            } else if (child instanceof BulletList || child instanceof OrderedList) {
                renderBlock(child, maxWidth, indent + 2, out);
            } else {
                renderBlock(child, maxWidth, indent + 2, out);
            }
            firstChild = false;
        }
    }

    private static List<Node> collectChildrenSkippingTaskMarker(Node parent) {
        List<Node> result = new ArrayList<>();
        boolean skipFirst = parent.getFirstChild() instanceof TaskListItemMarker;
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (skipFirst) {
                skipFirst = false;
                continue;
            }
            result.add(child);
        }
        return result;
    }

    // ---- Blockquote ---------------------------------------------------------

    private void renderBlockquote(BlockQuote bq, int maxWidth, int indent, List<String> out) {
        List<String> inner = new ArrayList<>();
        for (Node child = bq.getFirstChild(); child != null; child = child.getNext()) {
            renderBlock(child, maxWidth - 4, 0, inner);
        }
        if (inner.isEmpty()) return;
        String prefix = " ".repeat(indent) + Tk.codeFence("│") + " ";
        for (String line : inner) {
            out.add(prefix + line);
        }
    }

    // ---- Code blocks --------------------------------------------------------

    private void renderFencedCodeBlock(FencedCodeBlock cb, int maxWidth, int indent, List<String> out) {
        String lang = cb.getInfo();
        if (lang != null) lang = lang.split("\\s+", 2)[0];
        if (lang == null) lang = "";
        String indentStr = " ".repeat(indent);
        out.add(indentStr + codeFenceTop(lang));
        for (String cl : codeLiteralLines(cb.getLiteral())) {
            out.add(indentStr + codeLine(cl, lang));
        }
        // Only draw the bottom border for a genuinely closed fence; a still-open
        // fence (streaming) is closed by the caller once the closing fence arrives.
        Integer closingLen = cb.getClosingFenceLength();
        if (closingLen != null && closingLen > 0) {
            out.add(indentStr + codeFenceBottom());
        }
    }

    private void renderIndentedCodeBlock(IndentedCodeBlock cb, int maxWidth, int indent, List<String> out) {
        String indentStr = " ".repeat(indent);
        out.add(indentStr + codeFenceTop(""));
        for (String cl : codeLiteralLines(cb.getLiteral())) {
            out.add(indentStr + codeLine(cl, ""));
        }
        out.add(indentStr + codeFenceBottom());
    }

    // ---- code rendering: single source of truth -----------------------------
    // Shared by the parsed-block path (here) and MarkdownRenderer's streaming /
    // partial-preview paths, so the code-fence look is defined in exactly one place.

    /**
     * Splits a code block literal into display lines. commonmark block literals
     * end with a single trailing newline by convention; dropping it prevents a
     * spurious blank line before the closing fence.
     */
    static String[] codeLiteralLines(String literal) {
        if (literal == null || literal.isEmpty()) return new String[0];
        String body = literal.endsWith("\n") ? literal.substring(0, literal.length() - 1) : literal;
        return body.split("\\R", -1);
    }

    /**
     * Renders one code line: gutter + syntax/diff highlight. The gutter {@code │}
     * sits in column 0 so it lines up vertically with the rounded corners
     * ({@link #codeFenceTop}/{@link #codeFenceBottom}) into a continuous left edge.
     */
    static String codeLine(String raw, String lang) {
        boolean isDiff = "diff".equalsIgnoreCase(lang);
        String hl = isDiff
                ? DiffHighlighter.highlightAndRender(raw, 1).stream().findFirst().orElse(raw)
                : CodeHighlighter.highlight(lang == null ? "" : lang, raw);
        return Tk.codeFence("│") + " " + hl;
    }

    /** Opening fence line (rounded corner), optionally tagged with the language. */
    static String codeFenceTop(String lang) {
        String tag = (lang == null || lang.isEmpty()) ? "" : " " + lang;
        return Tk.codeFence("╭─" + tag);
    }

    /** Closing fence line (rounded corner). */
    static String codeFenceBottom() {
        return Tk.codeFence("╰─");
    }

    // ---- Tables -------------------------------------------------------------

    private void renderTableBlock(TableBlock tb, int maxWidth, int indent, List<String> out) {
        TableModel model = extractTableModel(tb);
        if (model == null || model.columnCount == 0) return;

        int availWidth = Math.max(20, maxWidth) - indent;
        List<String> horizontal = renderHorizontalTable(model, availWidth);
        if (horizontal != null) {
            for (String line : horizontal) {
                out.add(indent > 0 ? " ".repeat(indent) + line : line);
            }
            return;
        }

        List<String> vertical = renderVerticalTable(model, availWidth);
        for (String line : vertical) {
            out.add(indent > 0 ? " ".repeat(indent) + line : line);
        }
    }

    private TableModel extractTableModel(TableBlock table) {
        List<Cell> headers = new ArrayList<>();
        List<List<Cell>> rows = new ArrayList<>();
        List<String> alignments = new ArrayList<>();

        for (Node child = table.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof TableHead th) {
                for (Node rowNode = th.getFirstChild(); rowNode != null; rowNode = rowNode.getNext()) {
                    if (rowNode instanceof TableRow tr) {
                        headers.clear();
                        alignments.clear();
                        for (Node cellNode = tr.getFirstChild(); cellNode != null; cellNode = cellNode.getNext()) {
                            if (cellNode instanceof TableCell tc) {
                                headers.add(new Cell(cellToLines(tc)));
                                int colIdx = headers.size() - 1;
                                while (alignments.size() <= colIdx) alignments.add("left");
                                TableCell.Alignment a = tc.getAlignment();
                                if (a != null) {
                                    alignments.set(colIdx, switch (a) {
                                        case LEFT -> "left";
                                        case CENTER -> "center";
                                        case RIGHT -> "right";
                                    });
                                }
                            }
                        }
                    }
                }
            } else if (child instanceof TableBody tb) {
                for (Node rowNode = tb.getFirstChild(); rowNode != null; rowNode = rowNode.getNext()) {
                    if (rowNode instanceof TableRow tr) {
                        List<Cell> rowCells = new ArrayList<>();
                        for (Node cellNode = tr.getFirstChild(); cellNode != null; cellNode = cellNode.getNext()) {
                            if (cellNode instanceof TableCell tc) {
                                rowCells.add(new Cell(cellToLines(tc)));
                            }
                        }
                        if (!rowCells.isEmpty()) rows.add(rowCells);
                    }
                }
            }
        }

        if (headers.isEmpty() && rows.isEmpty()) return null;

        int columnCount = Math.max(headers.size(), 1);
        for (List<Cell> row : rows) {
            columnCount = Math.max(columnCount, row.size());
        }
        while (headers.size() < columnCount) headers.add(new Cell(List.of(InlineMarkdown.Line.empty())));
        while (alignments.size() < columnCount) alignments.add("left");
        for (List<Cell> row : rows) {
            while (row.size() < columnCount) row.add(new Cell(List.of(InlineMarkdown.Line.empty())));
        }
        return new TableModel(headers, rows, alignments, columnCount);
    }

    private List<InlineMarkdown.Line> cellToLines(TableCell tc) {
        return InlineMarkdown.collectLinesWithBreaks(tc);
    }

    // ---- Inline -> InlineMarkdown.Line conversion ---------------------------

    private InlineMarkdown.Line collectLine(Node container) {
        return InlineMarkdown.collectLine(container);
    }

    // ---- Table layout (ported from old MarkdownRenderer) --------------------

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

    static final class Cell {
        final List<InlineMarkdown.Line> logicalLines;

        Cell(List<InlineMarkdown.Line> logicalLines) {
            this.logicalLines = logicalLines;
        }
    }

    private List<String> renderHorizontalTable(TableModel model, int maxWidth) {
        int availableWidth = Math.max(20, maxWidth) - SAFETY_MARGIN;
        int borderOverhead = model.columnCount + 1 + model.columnCount * 2;
        int contentWidth = availableWidth - borderOverhead;
        if (contentWidth <= 0) return null;

        ColumnWidths widths = computeColumnWidths(model, contentWidth, availableWidth, borderOverhead);
        if (widths == null) return null;

        for (List<Cell> row : model.rows) {
            if (estimateRowHeight(row, widths.widths) > MAX_ROW_LINES) return null;
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
            if (Tk.displayWidth(line) > maxWidth) return null;
        }
        return lines;
    }

    private String renderTableBorder(int[] widths, String left, String join, String right) {
        StringBuilder sb = new StringBuilder();
        sb.append(Tk.dim(left));
        for (int col = 0; col < widths.length; col++) {
            if (col > 0) sb.append(Tk.dim(join));
            sb.append(Tk.dim("─".repeat(widths[col] + 2)));
        }
        sb.append(Tk.dim(right));
        return sb.toString();
    }

    private record ColumnWidths(int[] widths) {}

    private ColumnWidths computeColumnWidths(TableModel model, int contentWidth, int availableWidth, int borderOverhead) {
        int n = model.columnCount;
        int[] ideal = new int[n];
        int[] min = new int[n];
        int[] maxLW = new int[n];
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
            maxLW[col] = maxLineWidth;
        }

        int naturalTotal = sum(maxLW) + borderOverhead;
        if (naturalTotal <= availableWidth) {
            return new ColumnWidths(maxLW);
        }

        int idealTotal = sum(ideal) + borderOverhead;
        if (idealTotal <= availableWidth) {
            int[] widths = ideal.clone();
            int extra = availableWidth - borderOverhead - sum(widths);
            distributeTowardNatural(widths, maxLW, extra);
            return new ColumnWidths(widths);
        }

        int minTotal = sum(min) + borderOverhead;
        if (minTotal > availableWidth) return null;

        int[] widths = min.clone();
        int extra = availableWidth - borderOverhead - sum(widths);
        if (extra <= 0) return new ColumnWidths(widths);

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

    private static void distributeTowardNatural(int[] widths, int[] natural, int extra) {
        if (extra <= 0) return;
        int remaining = extra;
        while (remaining > 0) {
            int pick = -1;
            int bestNeed = -1;
            for (int i = 0; i < widths.length; i++) {
                int need = natural[i] - widths[i];
                if (need > bestNeed) {
                    bestNeed = need;
                    pick = i;
                }
            }
            if (pick < 0 || bestNeed <= 0) return;
            widths[pick]++;
            remaining--;
        }
    }

    private int estimateRowHeight(List<Cell> row, int[] widths) {
        int rowHeight = 1;
        for (int i = 0; i < widths.length; i++) {
            rowHeight = Math.max(rowHeight, wrapCell(row.get(i), widths[i]).size());
        }
        return rowHeight;
    }

    private List<String> renderHorizontalRow(List<Cell> row, int[] widths, List<String> alignments, boolean header) {
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
                if (col > 0) sb.append(" ").append(Tk.dim("│")).append(" ");
                List<InlineMarkdown.Line> wrapped = wrappedCells.get(col);
                InlineMarkdown.Line segment = lineIndex < wrapped.size() ? wrapped.get(lineIndex) : InlineMarkdown.Line.empty();
                String formatted = InlineMarkdown.render(segment);
                int display = InlineMarkdown.displayWidth(segment);
                sb.append(padCell(formatted, display, widths[col], alignments.get(col)));
            }
            sb.append(" ").append(Tk.dim("│"));
            lines.add(sb.toString());
        }
        return lines;
    }

    private List<String> renderVerticalTable(TableModel model, int maxWidth) {
        List<String> out = new ArrayList<>();
        boolean useTitleColumn = model.columnCount > 1 && preferFirstColumnAsTitle(model.headers.getFirst());

        for (int rowIndex = 0; rowIndex < model.rows.size(); rowIndex++) {
            if (rowIndex > 0) out.add("");
            List<Cell> row = model.rows.get(rowIndex);
            String title = useTitleColumn
                    ? firstNonBlank(row.getFirst(), "Row " + (rowIndex + 1))
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

        if (model.rows.isEmpty()) {
            String title = firstNonBlank(model.headers.getFirst(), "Table");
            out.addAll(prefixWrapped(title, "", "", maxWidth));
            for (int col = 1; col < model.columnCount; col++) {
                out.addAll(prefixWrapped(firstNonBlank(model.headers.get(col), "Column " + (col + 1)), "  ", "  ", maxWidth));
            }
        }
        return out;
    }

    private boolean preferFirstColumnAsTitle(Cell headerCell) {
        String normalized = firstNonBlank(headerCell, "").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
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

    private static final java.util.regex.Pattern BULLET_LINE = java.util.regex.Pattern.compile("^([•*\\-]|\\d+\\.)\\s+(.+)$");

    private List<InlineMarkdown.Line> wrapCell(Cell cell, int width) {
        if (width <= 0) return List.of(InlineMarkdown.Line.empty());
        List<InlineMarkdown.Line> out = new ArrayList<>();
        for (InlineMarkdown.Line logical : cell.logicalLines) {
            String plain = InlineMarkdown.plainText(logical).trim();
            if (plain.isEmpty()) {
                out.add(InlineMarkdown.Line.empty());
                continue;
            }
            java.util.regex.Matcher bullet = BULLET_LINE.matcher(plain);
            if (bullet.matches()) {
                out.addAll(wrapBulletLine(logical, bullet.group(1), width));
                continue;
            }
            out.addAll(InlineMarkdown.wrap(logical, width));
        }
        if (out.isEmpty()) out.add(InlineMarkdown.Line.empty());
        return out;
    }

    private List<InlineMarkdown.Line> wrapBulletLine(InlineMarkdown.Line line, String marker, int width) {
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
        String continuationPrefix = " ".repeat(prefix.length());
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

    private static String padCell(String content, int displayW, int targetW, String align) {
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

    // ---- Plain text wrap helpers -------------------------------------------

    private List<String> wrapPlainText(String text, int width) {
        if (text == null || text.isEmpty()) return List.of("");
        if (width <= 0) return List.of(text);
        List<String> lines = wordWrap(text, width);
        return lines.isEmpty() ? List.of("") : lines;
    }

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
            int end = TerminalText.clusterEnd(text, offset);
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

    private static int sum(int[] values) {
        int s = 0;
        for (int v : values) s += v;
        return s;
    }
}
