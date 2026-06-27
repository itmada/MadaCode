package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class FileEditTool implements Tool<FileEditTool.Input> {

    public record Input(
            String file_path,
            String old_string,
            String new_string,
            Boolean replace_all) {}

    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024; // 20 MiB
    private static final int MAX_DIFF_LINES = 200;
    private static final int MAX_DIFF_LINE_CHARS = 240;

    @Override
    public String name() {
        return ToolNames.FILE_EDIT;
    }

    @Override
    public String description() {
        return "Performs exact string replacements in an existing file. "
                + "Read the file first. old_string must match the file exactly, including indentation and whitespace. "
                + "Use the smallest clearly unique old_string, usually a few adjacent lines. "
                + "If old_string is not unique, include more surrounding context or set replace_all only when every occurrence should change. "
                + "Prefer editing existing files over creating new files.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public boolean isFileEdit() {
        return true;
    }

    @Override
    public String approvalSignature(ObjectNode input) {
        return "path:" + input.path("file_path").asText("");
    }

    @Override
    public List<String> permissionTargets(ObjectNode input) {
        String path = input.path("file_path").asText("");
        return path.isBlank() ? List.of() : List.of(path);
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("file_path", ToolSchemas.stringProperty(
                mapper, "The absolute path to the file to modify"));
        properties.set("old_string", ToolSchemas.stringProperty(
                mapper, "The text to replace"));
        properties.set("new_string", ToolSchemas.stringProperty(
                mapper, "The text to replace it with (must be different from old_string)"));
        properties.set("replace_all", ToolSchemas.booleanProperty(
                mapper, "Replace all occurrences of old_string (default false)"));
        return ToolSchemas.objectSchema(mapper, properties, "file_path", "old_string", "new_string");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        String filePath = input.file_path() == null ? "" : input.file_path();
        PathSafety.SafePathResult pathResult = PathSafety.validateForWrite(filePath);
        if (!pathResult.isValid()) {
            return new ToolResult(name(), false, pathResult.error());
        }
        Path target = pathResult.path();

        // .ipynb interception
        if (filePath.endsWith(".ipynb")) {
            return new ToolResult(name(), false,
                    "Cannot edit Jupyter notebooks with this tool. [errorCode=5]");
        }

        String oldString = input.old_string() == null ? "" : input.old_string();
        String newString = input.new_string() == null ? "" : input.new_string();
        boolean replaceAll = Boolean.TRUE.equals(input.replace_all());

        if (oldString.equals(newString)) {
            return new ToolResult(name(), false,
                    "No changes to make: old_string and new_string are exactly the same. [errorCode=1]");
        }

        if (!Files.exists(target)) {
            if (oldString.isEmpty()) {
                ToolResult createResult = createNewFile(target, newString);
                if (createResult.success()) {
                    context.session().readFileState().updateAfterWrite(target);
                }
                return createResult;
            }
            return new ToolResult(name(), false,
                    "File does not exist: " + filePath + " [errorCode=3]");
        }

        try {
            if (Files.size(target) > MAX_FILE_SIZE) {
                return new ToolResult(name(), false,
                        "File is too large to edit. Maximum size is 20 MiB. [errorCode=10]");
            }
        } catch (IOException e) {
            return new ToolResult(name(), false,
                    "Failed to read file size: " + e.getMessage());
        }

        if (Files.isDirectory(target)) {
            return new ToolResult(name(), false,
                    "Path is a directory: " + filePath + " [errorCode=11]");
        }

        if (!oldString.isEmpty()) {
            String staleError = context.session().readFileState().checkStaleness(target);
            if (staleError != null) {
                return new ToolResult(name(), false, staleError + " [errorCode=12]");
            }
        }
        if (FileSearchSupport.isLikelyBinary(target)) {
            return new ToolResult(name(), false,
                    "File appears to be binary and cannot be edited as text. [errorCode=11]");
        }

        TextFileSupport.TextSnapshot snapshot;
        try {
            snapshot = TextFileSupport.readPreservingLineSeparator(target);
        } catch (IOException e) {
            return new ToolResult(name(), false,
                    "Failed to read file: " + e.getMessage());
        }
        String normalizedContent = snapshot.content();
        String lineSeparator = snapshot.lineSeparator();
        String normalizedOldString = TextFileSupport.normalizeLineSeparators(oldString);
        String normalizedNewString = TextFileSupport.normalizeLineSeparators(newString);

        if (normalizedOldString.equals(normalizedNewString)) {
            return new ToolResult(name(), false,
                    "No changes to make: old_string and new_string are exactly the same. [errorCode=1]");
        }

        if (normalizedOldString.isEmpty()) {
            if (!normalizedContent.isEmpty()) {
                return new ToolResult(name(), false,
                        "Cannot create new file - file already exists and is not empty. [errorCode=3]");
            }
            ToolResult writeResult = writeWholeContent(target, normalizedNewString, filePath, lineSeparator);
            if (writeResult.success()) {
                context.session().readFileState().updateAfterWrite(target);
            }
            return writeResult;
        }

        String actualOldString = TextFileSupport.findActualString(normalizedContent, normalizedOldString);
        if (actualOldString == null) {
            return new ToolResult(name(), false,
                    "String to replace not found in file. [errorCode=8]\n"
                            + "String: " + truncateForError(oldString));
        }

        int matchCount = 0;
        int pos = 0;
        while ((pos = normalizedContent.indexOf(actualOldString, pos)) != -1) {
            matchCount++;
            pos += actualOldString.length();
        }

        if (matchCount > 1 && !replaceAll) {
            return new ToolResult(name(), false,
                    "Found " + matchCount + " matches of the string to replace, "
                            + "but replace_all is false. To replace all occurrences, "
                            + "set replace_all to true. To replace only one, provide more context. [errorCode=9]\n"
                            + "String: " + truncateForError(oldString));
        }

        ToolResult editResult = replaceAndWrite(
                target,
                normalizedContent,
                actualOldString,
                normalizedNewString,
                replaceAll,
                matchCount,
                filePath,
                lineSeparator);
        if (editResult.success()) {
            context.session().readFileState().updateAfterWrite(target);
        }
        return editResult;
    }

    private ToolResult createNewFile(Path target, String content) {
        String normalized = TextFileSupport.normalizeLineSeparators(content);
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            LineChangeStats stats = LineChangeStats.between("", normalized);
            return new ToolResult(name(), true,
                    "File created successfully at: " + target
                            + "\nLine changes: " + stats.formatPlain()
                            + "\nDiff:\n" + snippetDiff(target.toString(), "", normalized, 1));
        } catch (IOException e) {
            return new ToolResult(name(), false,
                    "Failed to create file: " + e.getMessage());
        }
    }

    private ToolResult replaceAndWrite(
            Path target,
            String original,
            String oldString,
            String newString,
            boolean replaceAll,
            int occurrenceCount,
            String filePath,
            String lineSeparator) {
        String updated;
        if (replaceAll) {
            updated = original.replace(oldString, newString);
        } else {
            updated = original.replaceFirst(
                    java.util.regex.Pattern.quote(oldString),
                    java.util.regex.Matcher.quoteReplacement(newString));
        }

        try {
            Files.writeString(target, TextFileSupport.restoreLineSeparators(updated, lineSeparator));
        } catch (IOException e) {
            return new ToolResult(name(), false,
                    "Failed to write file: " + e.getMessage());
        }

        LineChangeStats stats = LineChangeStats.between(original, updated);

        StringBuilder result = new StringBuilder();
        result.append("The file ").append(filePath)
                .append(" has been updated successfully");
        if (replaceAll) {
            result.append(". All occurrences were replaced");
        }
        result.append(".\nLine changes: ").append(stats.formatPlain())
                .append("\nDiff:\n")
                .append(snippetDiff(filePath, oldString, newString, occurrenceCount));
        return new ToolResult(name(), true, result.toString().trim());
    }

    private ToolResult writeWholeContent(Path target, String content, String filePath, String lineSeparator) {
        String normalized = TextFileSupport.normalizeLineSeparators(content);
        String toWrite = TextFileSupport.restoreLineSeparators(normalized, lineSeparator);
        try {
            Files.writeString(target, toWrite);
        } catch (IOException e) {
            return new ToolResult(name(), false,
                    "Failed to write file: " + e.getMessage());
        }

        LineChangeStats stats = LineChangeStats.between("", normalized);
        StringBuilder result = new StringBuilder();
        result.append("The file ").append(filePath)
                .append(" has been updated successfully.\n")
                .append("Line changes: ").append(stats.formatPlain())
                .append("\nDiff:\n")
                .append(snippetDiff(filePath, "", normalized, 1));
        return new ToolResult(name(), true, result.toString().trim());
    }

    private static String snippetDiff(String filePath, String before, String after, int occurrenceCount) {
        List<String> beforeLines = diffContentLines(before);
        List<String> afterLines = diffContentLines(after);
        List<String> out = new java.util.ArrayList<>();
        out.add("--- " + filePath);
        out.add("+++ " + filePath);
        out.add(occurrenceCount > 1 ? "@@ repeated " + occurrenceCount + " times @@" : "@@");
        for (String line : beforeLines) {
            out.add("-" + line);
        }
        for (String line : afterLines) {
            out.add("+" + line);
        }

        boolean truncated = out.size() > MAX_DIFF_LINES;
        int limit = Math.min(out.size(), MAX_DIFF_LINES);
        StringBuilder rendered = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                rendered.append('\n');
            }
            rendered.append(truncateDiffLine(out.get(i)));
        }
        if (truncated) {
            rendered.append('\n').append("... diff truncated ...");
        }
        return rendered.toString();
    }

    private static List<String> diffContentLines(String value) {
        if (value == null || value.isEmpty()) {
            return List.of();
        }
        String[] raw = value.split("\\n", -1);
        int length = raw.length;
        if (length > 0 && raw[length - 1].isEmpty()) {
            length--;
        }
        List<String> lines = new java.util.ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            lines.add(raw[i]);
        }
        return lines;
    }

    private static String truncateDiffLine(String line) {
        if (line.length() <= MAX_DIFF_LINE_CHARS) {
            return line;
        }
        return line.substring(0, MAX_DIFF_LINE_CHARS - 3) + "...";
    }

    private String truncateForError(String s) {
        if (s.length() <= 200) {
            return s;
        }
        return s.substring(0, 197) + "...";
    }
}
