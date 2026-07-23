#!/usr/bin/env bash
# Hidden judge: each stage individually plus the composed pipeline.
set -e
cat > _Check.java <<'EOF'
public class _Check {
    public static void main(String[] args) {
        String csv = "name, qty ,price\n\napple, 3,10\nbanana,5, 20\n";
        String[][] rows = CsvParser.parse(csv);
        if (rows.length != 3) {
            System.err.println("parse: expected 3 rows (blank line skipped), got " + rows.length);
            System.exit(1);
        }
        if (!rows[0][1].equals("qty") || !rows[1][0].equals("apple") || !rows[2][2].equals("20")) {
            System.err.println("parse: cells not trimmed/split correctly");
            System.exit(1);
        }
        if (StatsCollector.sumColumn(rows, 1) != 8) {
            System.err.println("sumColumn(qty) != 8");
            System.exit(1);
        }
        if (StatsCollector.sumColumn(rows, 2) != 30) {
            System.err.println("sumColumn(price) != 30");
            System.exit(1);
        }
        try {
            StatsCollector.sumColumn(new String[][] {{"h"}, {"oops"}}, 0);
            System.err.println("sumColumn should throw NumberFormatException on non-numeric");
            System.exit(1);
        } catch (NumberFormatException expected) {
            // ok
        }
        if (!ReportFormatter.format(1, 8).equals("SUM(1)=8")) {
            System.err.println("format(1,8) = " + ReportFormatter.format(1, 8));
            System.exit(1);
        }
        // Composed end-to-end
        String out = ReportFormatter.format(2, StatsCollector.sumColumn(CsvParser.parse(csv), 2));
        if (!out.equals("SUM(2)=30")) {
            System.err.println("pipeline output = " + out);
            System.exit(1);
        }
        System.out.println("ok");
    }
}
EOF
javac CsvParser.java StatsCollector.java ReportFormatter.java _Check.java
java _Check
