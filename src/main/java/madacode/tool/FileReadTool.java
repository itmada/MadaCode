package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class FileReadTool implements Tool<FileReadTool.Input> {

    public record Input(String path, Integer offset, Integer limit) {}

    private static final int DEFAULT_LIMIT_LINES = 2000;
    private static final int MAX_LIMIT_LINES = 20_000;
    private static final long MAX_READ_BYTES_WITHOUT_RANGE = 1_000_000L;
    private static final int MAX_OUTPUT_CHARS = 100_000;
    private static final int MAX_LINE_CHARS = 20_000;

    public FileReadTool() {
    }

    @Override
    public String name() {
        return "file_read";
    }

    @Override
    public String description() {
        return "Reads text files with line-based pagination. Returns numbered lines. "
                + "Read a file before editing it. Prefer reading the whole relevant file when practical; "
                + "use offset/limit for large files or known locations. Do not include line-number prefixes "
                + "when copying text into file_edit old_string. If the path is a directory, use glob or bash ls instead.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(Input input) {
        return true;
    }

    @Override
    public List<String> permissionTargets(ObjectNode input) {
        String path = input.path("path").asText("");
        return path.isBlank() ? List.of() : List.of(path);
    }

    @Override
    public String approvalSignature(ObjectNode input) {
        String path = input.path("path").asText("");
        return path.isBlank() ? madacode.permission.CanonicalJson.canonicalize(input) : "path:" + path;
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("path", ToolSchemas.stringProperty(
                mapper, "Path to read, relative to the working directory."));
        properties.set("offset", ToolSchemas.integerProperty(
                mapper, "Optional 1-based starting line.", 1, 1_000_000));
        properties.set("limit", ToolSchemas.integerProperty(
                mapper, "Optional maximum number of lines. Default 2000, max 20000.", 1, MAX_LIMIT_LINES));
        return ToolSchemas.objectSchema(mapper, properties, "path");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        String path = input.path();
        if (path == null || path.isBlank()) {
            return new ToolResult(name(), false, "Missing required field: path");
        }

        ReadPathPolicy.ResolvedPath resolvedPath = ReadPathPolicy.resolveWithinWorkingDirectory(
                path, context.workingDirectory(), "path");
        if (!resolvedPath.isValid()) {
            return new ToolResult(name(), false, resolvedPath.error());
        }
        Path target = resolvedPath.path();
        try {
            if (!Files.exists(target)) {
                return new ToolResult(name(), false, "Path does not exist: " + target);
            }
            if (Files.isDirectory(target)) {
                return new ToolResult(name(), false, "Path is a directory");
            }
            if (FileSearchSupport.isLikelyBinary(target)) {
                return new ToolResult(name(), false, "Cannot read binary file as text");
            }
            if (input.offset() != null && input.offset() < 1) {
                return new ToolResult(name(), false, "offset must be >= 1");
            }
            if (input.limit() != null && (input.limit() < 1 || input.limit() > MAX_LIMIT_LINES)) {
                return new ToolResult(name(), false, "limit must be between 1 and " + MAX_LIMIT_LINES);
            }
            boolean isPartialView = input.offset() != null || input.limit() != null;
            String content = readTextLines(target, input);
            context.session().readFileState().record(target, isPartialView);
            return new ToolResult(name(), true, content);
        } catch (IOException exception) {
            return new ToolResult(name(), false, "Failed to read file: " + exception.getMessage());
        }
    }

    private String readTextLines(Path target, Input input) throws IOException {
        boolean explicitRange = input.offset() != null || input.limit() != null;
        int offset = input.offset() == null ? 1 : input.offset();
        int limit = input.limit() == null ? DEFAULT_LIMIT_LINES : input.limit();
        long fileSize = Files.exists(target) ? Files.size(target) : 0L;

        int startLine = offset;
        int endLine = offset - 1;
        int currentLineNumber = 0;
        int emitted = 0;
        boolean hasMoreLines = false;
        boolean outputTruncated = false;
        boolean truncated = false;
        StringBuilder out = new StringBuilder();

        try (BufferedReader reader = Files.newBufferedReader(target)) {
            String line;
            while ((line = reader.readLine()) != null) {
                currentLineNumber++;
                if (currentLineNumber < offset) {
                    continue;
                }
                if (emitted >= limit) {
                    hasMoreLines = true;
                    truncated = true;
                    break;
                }

                String lineToShow = line;
                if (lineToShow.length() > MAX_LINE_CHARS) {
                    lineToShow = lineToShow.substring(0, MAX_LINE_CHARS) + "...[line truncated]";
                    truncated = true;
                }

                AppendResult appendResult = appendLineWithinLimit(out, currentLineNumber, lineToShow);
                if (!appendResult.appended()) {
                    outputTruncated = true;
                    truncated = true;
                    break;
                }

                emitted++;
                endLine = currentLineNumber;
                if (appendResult.reachedOutputLimit()) {
                    outputTruncated = true;
                    truncated = true;
                    break;
                }
            }
            if (!hasMoreLines && emitted >= limit && reader.readLine() != null) {
                hasMoreLines = true;
                truncated = true;
            }
        }

        if (!explicitRange && fileSize > MAX_READ_BYTES_WITHOUT_RANGE) {
            truncated = true;
        }

        if (outputTruncated) {
            appendNoticeLine(out, "[Output truncated at " + MAX_OUTPUT_CHARS + " characters.]");
        }

        if (truncated) {
            appendNoticeLine(out, "[File truncated: showing lines "
                    + startLine
                    + "-"
                    + Math.max(endLine, offset - 1)
                    + ". Use offset and limit to read more.]");
        }
        return out.toString();
    }

    private AppendResult appendLineWithinLimit(StringBuilder out, int lineNumber, String line) {
        String prefix = out.isEmpty() ? "" : "\n";
        String rendered = prefix + lineNumber + '\t' + line;
        int remaining = MAX_OUTPUT_CHARS - out.length();
        if (remaining <= 0) {
            return new AppendResult(false, true);
        }
        if (rendered.length() <= remaining) {
            out.append(rendered);
            return new AppendResult(true, false);
        }
        out.append(rendered, 0, remaining);
        return new AppendResult(true, true);
    }

    private void appendNoticeLine(StringBuilder out, String notice) {
        if (out.length() > 0) {
            out.append('\n');
        }
        out.append(notice);
    }

    private record AppendResult(boolean appended, boolean reachedOutputLimit) {}
}
