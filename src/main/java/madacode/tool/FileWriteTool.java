package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.ToolResult;
import madacode.core.ToolUseContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class FileWriteTool implements Tool<FileWriteTool.Input> {

    public record Input(String file_path, String content) {}
    private static final long MAX_DIFF_SOURCE_BYTES = 2L * 1024 * 1024; // 2 MiB

    @Override
    public String name() {
        return "write";
    }

    @Override
    public String description() {
        return "Writes a file to the local filesystem. "
                + "Creates the file if it doesn't exist, overwrites it if it does. "
                + "File path must be absolute.";
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
                mapper, "The absolute path to the file to write (must be absolute, not relative)"));
        properties.set("content", ToolSchemas.stringProperty(
                mapper, "The content to write to the file"));
        return ToolSchemas.objectSchema(mapper, properties, "file_path", "content");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        PathSafety.SafePathResult pathResult = PathSafety.validateForWrite(
                input.file_path() == null ? "" : input.file_path());
        if (!pathResult.isValid()) {
            return new ToolResult(name(), false, pathResult.error());
        }
        Path target = pathResult.path();

        String content = input.content() == null ? "" : input.content();

        boolean exists = Files.exists(target);
        String originalContent = null;
        String diffSkippedReason = null;
        boolean canGenerateDiff = false;

        if (exists) {
            if (Files.isDirectory(target)) {
                return new ToolResult(name(), false, "Path is a directory");
            }
            String staleError = context.session().readFileState().checkStaleness(target);
            if (staleError != null) {
                return new ToolResult(name(), false, staleError + " [errorCode=12]");
            }
            try {
                long size = Files.size(target);
                if (size > MAX_DIFF_SOURCE_BYTES) {
                    diffSkippedReason = "existing file is larger than 2 MiB.";
                } else if (FileSearchSupport.isLikelyBinary(target)) {
                    diffSkippedReason = "existing file appears to be binary.";
                } else {
                    try {
                        originalContent = Files.readString(target);
                        canGenerateDiff = true;
                    } catch (IOException readException) {
                        diffSkippedReason = "existing file could not be read as text: "
                                + readException.getMessage();
                    }
                }
            } catch (IOException e) {
                return new ToolResult(name(), false,
                        "Failed to inspect existing file: " + e.getMessage());
            }
        } else {
            try {
                Files.createDirectories(target.getParent());
            } catch (IOException e) {
                return new ToolResult(name(), false,
                        "Failed to create parent directory: " + e.getMessage());
            }
        }

        try {
            Files.writeString(target, content);
        } catch (IOException e) {
            return new ToolResult(name(), false,
                    "Failed to write file: " + e.getMessage());
        }

        context.session().readFileState().updateAfterWrite(target);

        String displayPath = target.toString();
        if (exists) {
            StringBuilder result = new StringBuilder();
            result.append("The file ").append(displayPath)
                    .append(" has been updated successfully.\n");
            if (canGenerateDiff) {
                List<String> patch = generatePatch(originalContent, content, displayPath);
                if (!patch.isEmpty()) {
                    result.append("Changes:\n");
                    for (String line : patch) {
                        result.append(line).append('\n');
                    }
                }
            } else if (diffSkippedReason != null) {
                result.append("Diff skipped: ").append(diffSkippedReason).append('\n');
            }
            return new ToolResult(name(), true, result.toString().trim());
        } else {
            return new ToolResult(name(), true,
                    "File created successfully at: " + displayPath);
        }
    }

    private List<String> generatePatch(String original, String updated, String filePath) {
        return computeUnifiedDiff(original, updated, filePath);
    }

    static List<String> computeUnifiedDiff(String original, String updated, String filePath) {
        return UnifiedDiffSupport.computeUnifiedDiff(original, updated, filePath);
    }

    static List<String> computeUnifiedDiff(
            List<String> originalLines,
            List<String> updatedLines,
            String filePath) {
        return UnifiedDiffSupport.computeUnifiedDiff(originalLines, updatedLines, filePath);
    }
}
