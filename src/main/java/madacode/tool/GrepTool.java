package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.ToolResult;
import madacode.core.ToolUseContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class GrepTool implements Tool<GrepTool.Input> {

    public record Input(
            String pattern,
            String path,
            String glob,
            Boolean caseInsensitive,
            String outputMode,
            Integer context,
            Integer headLimit) {}

    private static final String MODE_FILES = "files_with_matches";
    private static final String MODE_CONTENT = "content";
    private static final String MODE_COUNT = "count";
    private static final int DEFAULT_HEAD_LIMIT = 250;
    private static final int PROGRESS_EVERY_FILES = 200;
    private static final long PROGRESS_MIN_INTERVAL_MS = 250;

    @Override
    public String name() {
        return "grep";
    }

    @Override
    public String description() {
        return "Searches for regex matches in files under the working directory.";
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
                mapper, "Regex pattern to search for."));
        properties.set("path", ToolSchemas.stringProperty(
                mapper, "Optional file or directory path to search under."));
        properties.set("glob", ToolSchemas.stringProperty(
                mapper, "Optional glob filter applied to paths relative to the searched directory, for example "
                        + "\"*.java\" or \"src/**/*.java\"."));
        properties.set("caseInsensitive", ToolSchemas.booleanProperty(
                mapper, "Whether matching is case-insensitive. Defaults to false."));
        properties.set("outputMode", ToolSchemas.stringEnumProperty(
                mapper,
                "Output mode: files_with_matches (default), content, or count.",
                MODE_FILES,
                MODE_CONTENT,
                MODE_COUNT));
        properties.set("context", ToolSchemas.integerProperty(
                mapper, "Number of context lines around matches for content mode.", 0, 2000));
        properties.set("headLimit", ToolSchemas.integerProperty(
                mapper,
                "Maximum number of matches to return. Default 250.",
                1,
                FileSearchSupport.MAX_RESULTS));
        return ToolSchemas.objectSchema(mapper, properties, "pattern");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        String pattern = input.pattern();
        if (pattern == null || pattern.isBlank()) {
            return new ToolResult(name(), false, "Missing required field: pattern");
        }

        Pattern compiledPattern;
        try {
            int flags = Boolean.TRUE.equals(input.caseInsensitive()) ? Pattern.CASE_INSENSITIVE : 0;
            compiledPattern = Pattern.compile(pattern, flags);
        } catch (PatternSyntaxException exception) {
            return new ToolResult(name(), false, "Invalid regex pattern: " + exception.getMessage());
        }

        String outputMode = input.outputMode() == null || input.outputMode().isBlank()
                ? MODE_FILES
                : input.outputMode();
        if (!MODE_FILES.equals(outputMode) && !MODE_CONTENT.equals(outputMode) && !MODE_COUNT.equals(outputMode)) {
            return new ToolResult(name(), false, "Invalid outputMode: " + outputMode);
        }

        int contextLines = input.context() == null ? 0 : input.context();
        if (contextLines < 0) {
            return new ToolResult(name(), false, "context must be >= 0");
        }

        int headLimit = input.headLimit() == null ? DEFAULT_HEAD_LIMIT : input.headLimit();
        if (headLimit < 1 || headLimit > FileSearchSupport.MAX_RESULTS) {
            return new ToolResult(name(), false, "headLimit must be between 1 and " + FileSearchSupport.MAX_RESULTS);
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

        PathMatcher pathGlobMatcher = input.glob() == null || input.glob().isBlank()
                ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + input.glob());
        Path globRoot = Files.isRegularFile(root)
                ? (root.getParent() == null ? context.workingDirectory() : root.getParent())
                : root;
        boolean emitProgress = Files.isDirectory(root);
        ProgressEmitter progress = new ProgressEmitter(context.session(), PROGRESS_MIN_INTERVAL_MS);

        SearchAccumulator accumulator = new SearchAccumulator(headLimit);
        try {
            if (Files.isRegularFile(root)) {
                searchSingleFile(
                        root,
                        globRoot,
                        displayBase,
                        pathGlobMatcher,
                        compiledPattern,
                        outputMode,
                        contextLines,
                        accumulator);
            } else {
                SearchProgressTracker tracker = new SearchProgressTracker(progress, emitProgress, accumulator);
                FileSearchSupport.walkFiles(root, candidate -> {
                    tracker.onCandidateFile();
                    searchSingleFile(
                            candidate,
                            globRoot,
                            displayBase,
                            pathGlobMatcher,
                            compiledPattern,
                            outputMode,
                            contextLines,
                            accumulator);
                    return !accumulator.truncated();
                });
            }
        } catch (IOException exception) {
            return new ToolResult(name(), false, "Grep failed: " + exception.getMessage());
        }

        String output = String.join(System.lineSeparator(), accumulator.lines());
        if (accumulator.truncated()) {
            if (!output.isEmpty()) {
                output += System.lineSeparator();
            }
            output += "[Results truncated at " + headLimit + " matches]";
        }
        return new ToolResult(name(), true, output);
    }

    private void searchSingleFile(
            Path file,
            Path globRoot,
            Path cwd,
            PathMatcher pathGlobMatcher,
            Pattern pattern,
            String outputMode,
            int contextLines,
            SearchAccumulator accumulator) throws IOException {
        if (accumulator.truncated()) {
            return;
        }
        if (FileSearchSupport.isExcluded(cwd, file) || FileSearchSupport.isLikelyBinary(file)) {
            return;
        }
        if (pathGlobMatcher != null) {
            Path relativePath = globRoot.relativize(file);
            if (!pathGlobMatcher.matches(relativePath)) {
                return;
            }
        }

        String displayPath = FileSearchSupport.relativizeOrAbsolute(cwd, file);
        if (MODE_CONTENT.equals(outputMode)) {
            collectContentMatches(file, pattern, contextLines, displayPath, accumulator);
            return;
        }

        int matchedLines = countMatchingLines(file, pattern);
        if (matchedLines <= 0) {
            return;
        }
        if (!accumulator.tryRecordMatch()) {
            return;
        }
        if (MODE_COUNT.equals(outputMode)) {
            accumulator.addLine(displayPath + ":" + matchedLines);
            return;
        }
        accumulator.addLine(displayPath);
    }

    private int countMatchingLines(Path file, Pattern pattern) throws IOException {
        int count = 0;
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (pattern.matcher(line).find()) {
                    count++;
                }
            }
        }
        return count;
    }

    private void collectContentMatches(
            Path file,
            Pattern pattern,
            int contextLines,
            String displayPath,
            SearchAccumulator accumulator) throws IOException {
        Deque<LineRef> previous = new ArrayDeque<>();
        int afterContextRemaining = 0;
        int lastEmittedLine = 0;
        int lineNumber = 0;

        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                boolean matched = pattern.matcher(line).find();
                if (matched) {
                    if (!accumulator.tryRecordMatch()) {
                        return;
                    }
                    for (LineRef contextLine : previous) {
                        if (contextLine.number() > lastEmittedLine) {
                            accumulator.addLine(formatContentLine(displayPath, contextLine.number(), contextLine.text()));
                            lastEmittedLine = contextLine.number();
                        }
                    }
                    if (lineNumber > lastEmittedLine) {
                        accumulator.addLine(formatContentLine(displayPath, lineNumber, line));
                        lastEmittedLine = lineNumber;
                    }
                    afterContextRemaining = contextLines;
                } else if (afterContextRemaining > 0) {
                    if (lineNumber > lastEmittedLine) {
                        accumulator.addLine(formatContentLine(displayPath, lineNumber, line));
                        lastEmittedLine = lineNumber;
                    }
                    afterContextRemaining--;
                }

                if (contextLines > 0) {
                    previous.addLast(new LineRef(lineNumber, line));
                    while (previous.size() > contextLines) {
                        previous.removeFirst();
                    }
                }

                if (accumulator.truncated()) {
                    return;
                }
            }
        }
    }

    private String formatContentLine(String displayPath, int lineNumber, String line) {
        return displayPath + ":" + lineNumber + ":" + line;
    }

    private record LineRef(int number, String text) {}

    private static final class SearchAccumulator {
        private final int limit;
        private final List<String> lines = new ArrayList<>();
        private int matchCount = 0;
        private boolean truncated = false;

        private SearchAccumulator(int limit) {
            this.limit = limit;
        }

        private boolean tryRecordMatch() {
            if (matchCount >= limit) {
                truncated = true;
                return false;
            }
            matchCount++;
            return true;
        }

        private void addLine(String line) {
            lines.add(line);
        }

        private List<String> lines() {
            return lines;
        }

        private int matchCount() {
            return matchCount;
        }

        private boolean truncated() {
            return truncated;
        }
    }

    private static final class SearchProgressTracker {
        private final ProgressEmitter progress;
        private final boolean enabled;
        private final SearchAccumulator accumulator;
        private int scannedFiles;

        private SearchProgressTracker(ProgressEmitter progress, boolean enabled, SearchAccumulator accumulator) {
            this.progress = progress;
            this.enabled = enabled;
            this.accumulator = accumulator;
        }

        private void onCandidateFile() {
            if (!enabled) {
                return;
            }
            scannedFiles++;
            if (scannedFiles >= PROGRESS_EVERY_FILES && scannedFiles % PROGRESS_EVERY_FILES == 0) {
                progress.emitMetricThrottled(
                        "scanned " + scannedFiles + " files · " + accumulator.matchCount() + " matches");
            }
        }
    }
}
