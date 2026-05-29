package madacode.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class TextFileSupport {

    private TextFileSupport() {
    }

    record TextSnapshot(String content, String lineSeparator) {}

    static TextSnapshot readPreservingLineSeparator(Path target) throws IOException {
        String original = Files.readString(target);
        String lineSeparator = detectLineSeparator(original);
        return new TextSnapshot(normalizeLineSeparators(original), lineSeparator);
    }

    static String normalizeLineSeparators(String content) {
        return content.replace("\r\n", "\n").replace("\r", "\n");
    }

    static String restoreLineSeparators(String normalized, String lineSeparator) {
        if ("\n".equals(lineSeparator)) {
            return normalized;
        }
        return normalized.replace("\n", lineSeparator);
    }

    private static String detectLineSeparator(String content) {
        int length = content.length();
        for (int i = 0; i < length; i++) {
            char c = content.charAt(i);
            if (c == '\n') {
                return "\n";
            }
            if (c == '\r') {
                if (i + 1 < length && content.charAt(i + 1) == '\n') {
                    return "\r\n";
                }
                return "\r";
            }
        }
        return "\n";
    }

    static String normalizeQuotes(String s) {
        return s
                .replace('‘', '\'')  // left single curly
                .replace('’', '\'')  // right single curly
                .replace('“', '"')   // left double curly
                .replace('”', '"');  // right double curly
    }

    static String findActualString(String fileContent, String searchString) {
        if (fileContent.contains(searchString)) {
            return searchString;
        }
        String normalizedSearch = normalizeQuotes(searchString);
        String normalizedFile = normalizeQuotes(fileContent);
        int index = normalizedFile.indexOf(normalizedSearch);
        if (index != -1) {
            return fileContent.substring(index, index + normalizedSearch.length());
        }
        return null;
    }
}
