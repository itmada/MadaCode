package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class GlobTool implements Tool<GlobTool.Input> {

    public record Input(String pattern, String path, Integer limit) {}
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    @Override
    public String name() {
        return "glob";
    }

    @Override
    public String description() {
        return "Finds files using a glob pattern. Results are returned in filesystem traversal order.";
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
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("pattern", ToolSchemas.stringProperty(
                mapper, "Glob pattern, for example src/**/*.java."));
        properties.set("path", ToolSchemas.stringProperty(
                mapper, "Optional directory path to search under. Defaults to the working directory."));
        properties.set("limit", ToolSchemas.integerProperty(
                mapper, "Maximum files to return. Default 100, max 1000.", 1, MAX_LIMIT));
        return ToolSchemas.objectSchema(mapper, properties, "pattern");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        String pattern = input.pattern();
        if (pattern == null || pattern.isBlank()) {
            return new ToolResult(name(), false, "Missing required field: pattern");
        }

        int limit = input.limit() == null ? DEFAULT_LIMIT : input.limit();
        if (limit < 1 || limit > MAX_LIMIT) {
            return new ToolResult(name(), false, "limit must be between 1 and " + MAX_LIMIT);
        }

        ReadPathPolicy.ResolvedPath resolvedRoot = ReadPathPolicy.resolveWithinWorkingDirectory(
                input.path(), context.workingDirectory(), "path");
        if (!resolvedRoot.isValid()) {
            return new ToolResult(name(), false, resolvedRoot.error());
        }
        Path root = resolvedRoot.path();
        Path displayBase = resolvedRoot.displayBase();
        if (!Files.exists(root)) {
            return new ToolResult(name(), false, "Path does not exist: " + root);
        }
        if (!Files.isDirectory(root)) {
            return new ToolResult(name(), false, "Path is not a directory: " + root);
        }

        FileSystem fs = FileSystems.getDefault();
        var matcher = fs.getPathMatcher("glob:" + pattern);
        try {
            List<String> matches = new ArrayList<>();
            FileSearchSupport.walkFiles(root, file -> {
                Path relative = root.relativize(file);
                if (matcher.matches(relative)) {
                    matches.add(FileSearchSupport.relativizeOrAbsolute(displayBase, file));
                }
                return matches.size() <= limit;
            });

            boolean truncated = matches.size() > limit;
            List<String> visibleMatches = truncated ? matches.subList(0, limit) : matches;
            String output = String.join(System.lineSeparator(), visibleMatches);
            if (truncated) {
                if (!output.isEmpty()) {
                    output += System.lineSeparator();
                }
                output += "[Results truncated at " + limit + " files]";
            }
            return new ToolResult(name(), true, output);
        } catch (IOException exception) {
            return new ToolResult(name(), false, "Glob failed: " + exception.getMessage());
        }
    }

}
