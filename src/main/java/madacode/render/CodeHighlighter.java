package madacode.render;

import madacode.tui.theme.Tk;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight keyword-based syntax highlighting for code blocks in the
 * terminal. Language detection is driven by the markdown fence's info
 * string (e.g. {@code ```java}). Unknown or missing languages pass through
 * uncolored.
 *
 * <p>No AST parsing — just regex tokenising. The results are "better than
 * nothing" and cheap enough to run on streaming output in real-time.
 */
public final class CodeHighlighter {

    private static final Map<String, RuleSet> LANGUAGES = Map.of(
            "java",    new RuleSet(Set.of(
                    "abstract", "assert", "boolean", "break", "byte", "case", "catch",
                    "char", "class", "continue", "default", "do", "double", "else",
                    "enum", "extends", "final", "finally", "float", "for", "if",
                    "implements", "import", "instanceof", "int", "interface", "long",
                    "native", "new", "package", "private", "protected", "public",
                    "return", "short", "static", "strictfp", "super", "switch",
                    "synchronized", "this", "throw", "throws", "transient", "try",
                    "void", "volatile", "while", "var", "record", "sealed", "permits",
                    "yield", "true", "false", "null"),
                    Tk::bold),

            "bash",    new RuleSet(Set.of(
                    "if", "then", "else", "elif", "fi", "case", "esac", "for",
                    "while", "until", "do", "done", "in", "function", "return",
                    "exit", "export", "local", "source", "echo", "cd", "ls",
                    "rm", "cp", "mv", "mkdir", "grep", "find", "sed", "awk",
                    "true", "false", "test"),
                    Tk::bold),

            "json",    new RuleSet(Set.of("true", "false", "null"),
                    Tk::bold),
            "xml",     new RuleSet(Set.of(),
                    Tk::bold),
            "python",  new RuleSet(Set.of(
                    "False", "None", "True", "and", "as", "assert", "async",
                    "await", "break", "class", "continue", "def", "del", "elif",
                    "else", "except", "finally", "for", "from", "global", "if",
                    "import", "in", "is", "lambda", "nonlocal", "not", "or",
                    "pass", "raise", "return", "try", "while", "with", "yield"),
                    Tk::bold),

            "sql",     new RuleSet(Set.of(
                    "SELECT", "FROM", "WHERE", "AND", "OR", "NOT", "IN",
                    "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE",
                    "CREATE", "TABLE", "DROP", "ALTER", "INDEX", "JOIN",
                    "LEFT", "RIGHT", "INNER", "OUTER", "ON", "AS", "ORDER",
                    "BY", "GROUP", "HAVING", "LIMIT", "OFFSET", "UNION",
                    "NULL", "TRUE", "FALSE", "COUNT", "SUM", "AVG", "MAX", "MIN"),
                    Tk::bold),

            "javascript", new RuleSet(Set.of(
                    "const", "let", "var", "function", "return", "if", "else",
                    "for", "while", "do", "switch", "case", "break", "continue",
                    "try", "catch", "finally", "throw", "new", "this", "class",
                    "extends", "import", "export", "default", "from", "async",
                    "await", "typeof", "instanceof", "true", "false", "null",
                    "undefined", "yield"),
                    Tk::bold),

            "typescript", new RuleSet(Set.of(
                    "const", "let", "var", "function", "return", "if", "else",
                    "for", "while", "do", "switch", "case", "break", "continue",
                    "try", "catch", "finally", "throw", "new", "this", "class",
                    "extends", "implements", "import", "export", "default", "from",
                    "async", "await", "typeof", "instanceof", "true", "false",
                    "null", "undefined", "yield", "type", "interface", "enum",
                    "readonly", "private", "protected", "public", "static",
                    "abstract", "as", "keyof"),
                    Tk::bold));

    private CodeHighlighter() {}

    /**
     * Highlights a single line of code within a fenced block. Unknown
     * languages pass through uncolored.
     */
    public static String highlight(String lang, String line) {
        if (lang == null || lang.isEmpty()) return line;
        RuleSet rules = LANGUAGES.get(lang);
        if (rules == null) return line;
        return rules.apply(line);
    }

    // ---- token types -------------------------------------------------

    /** Holds a keyword set and a styling function. */
    private record RuleSet(Set<String> keywords, java.util.function.Function<String, String> styler) {

        /** Anchored keyword match: only whole-word identifiers. */
        private static final Pattern IDENT = Pattern.compile("\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b");
        // Inline comments
        private static final Pattern COMMENT = Pattern.compile("(//.*|#.*|--\\s.*)$");

        String apply(String line) {
            // Dim comments
            line = COMMENT.matcher(line).replaceAll(
                    m -> Matcher.quoteReplacement(Tk.dim(m.group())));
            // Highlight keywords
            line = IDENT.matcher(line).replaceAll(m -> {
                String word = m.group(1);
                return keywords.contains(word)
                        ? Matcher.quoteReplacement(styler.apply(word))
                        : Matcher.quoteReplacement(word);
            });
            return line;
        }
    }
}
