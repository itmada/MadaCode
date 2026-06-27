package madacode.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.FileSystems;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Scores declarative tool/file trajectory constraints against the collected trace. */
public final class TrajectoryScorer implements Scorer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> READ_TOOL_NAMES = Set.of("file_read");
    private static final Set<String> WRITE_TOOL_NAMES = Set.of("file_edit", "file_write");
    private static final List<String> READ_FAILURE_PREFIXES = List.of(
            "Missing required field:",
            "Path does not exist:",
            "Path is a directory",
            "Cannot read binary file as text",
            "offset must be >=",
            "limit must be between",
            "Failed to read file:",
            "Invalid tool input for ",
            "Permission denied:",
            "Hook rejected:",
            "Cancelled",
            "Plan mode active",
            "Error:");

    @Override
    public Dimension dimension() {
        return Dimension.TRAJECTORY;
    }

    @Override
    public boolean appliesTo(EvalCase evalCase) {
        return evalCase.checks().trajectory() != null;
    }

    @Override
    public boolean gating(EvalCase evalCase) {
        return evalCase.checks().trajectory().gatingOrDefault();
    }

    @Override
    public DimensionScore score(EvalCase evalCase, ScoringContext context) {
        TrajectoryChecks checks = evalCase.checks().trajectory();
        List<String> violations = new ArrayList<>();
        ExecutionTrace trace = context.trace();

        checkAllowedTools(checks, trace.invocations(), violations);
        checkForbiddenTools(checks, trace.invocations(), violations);
        checkFileWhitelist(checks, trace.fileEffects(), violations);
        if (checks.requireReadBeforeEdit()) {
            checkReadBeforeEdit(context.environment().workspace(), trace, violations);
        }

        return result(
                evalCase,
                violations.isEmpty() ? EvalResult.JudgeStatus.PASS : EvalResult.JudgeStatus.FAIL,
                violations.isEmpty() ? "All trajectory checks passed." : String.join("\n", violations));
    }

    private static void checkAllowedTools(
            TrajectoryChecks checks,
            List<ToolInvocation> invocations,
            List<String> violations) {
        if (checks.allowedTools().isEmpty()) {
            return;
        }
        Set<String> allowed = Set.copyOf(checks.allowedTools());
        for (ToolInvocation invocation : invocations) {
            if (!allowed.contains(invocation.name())) {
                violations.add("Unexpected tool " + invocation.name() + " at #" + invocation.ordinal() + ".");
            }
        }
    }

    private static void checkForbiddenTools(
            TrajectoryChecks checks,
            List<ToolInvocation> invocations,
            List<String> violations) {
        if (checks.forbiddenTools().isEmpty()) {
            return;
        }
        Set<String> forbidden = Set.copyOf(checks.forbiddenTools());
        for (ToolInvocation invocation : invocations) {
            if (forbidden.contains(invocation.name())) {
                violations.add("Forbidden tool " + invocation.name() + " used at #" + invocation.ordinal() + ".");
            }
        }
    }

    private static void checkFileWhitelist(
            TrajectoryChecks checks,
            List<TouchedFile> fileEffects,
            List<String> violations) {
        if (checks.fileWhitelist().isEmpty()) {
            return;
        }
        List<PathMatcher> matchers = checks.fileWhitelist().stream()
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .toList();
        for (TouchedFile fileEffect : fileEffects) {
            String relPath = normalizeRelativePath(fileEffect.relPath());
            Path path = Path.of(relPath);
            boolean matched = matchers.stream().anyMatch(matcher -> matcher.matches(path));
            if (!matched) {
                violations.add("Touched file outside whitelist: " + relPath + ".");
            }
        }
    }

    private static void checkReadBeforeEdit(
            Path workspace,
            ExecutionTrace trace,
            List<String> violations) {
        Map<String, Integer> firstProvableReadOrdinalByPath = new HashMap<>();
        Map<String, Integer> firstWriteOrdinalByPath = new HashMap<>();
        List<ToolInvocation> orderedInvocations = trace.invocations().stream()
                .sorted(Comparator.comparingInt(ToolInvocation::ordinal))
                .toList();

        for (ToolInvocation invocation : orderedInvocations) {
            if (READ_TOOL_NAMES.contains(invocation.name())) {
                String relPath = extractNormalizedPath(workspace, invocation.inputJson(), "path");
                if (relPath != null && isProvableReadSuccess(invocation.resultJson())) {
                    firstProvableReadOrdinalByPath.merge(relPath, invocation.ordinal(), Math::min);
                }
                continue;
            }
            if (WRITE_TOOL_NAMES.contains(invocation.name())) {
                String relPath = extractNormalizedPath(workspace, invocation.inputJson(), "file_path");
                if (relPath != null) {
                    firstWriteOrdinalByPath.merge(relPath, invocation.ordinal(), Math::min);
                }
            }
        }

        for (TouchedFile fileEffect : trace.fileEffects()) {
            if (fileEffect.kind() == TouchedFile.ChangeKind.CREATED) {
                continue;
            }
            String relPath = normalizeRelativePath(fileEffect.relPath());
            Integer firstWriteOrdinal = firstWriteOrdinalByPath.get(relPath);
            if (firstWriteOrdinal == null) {
                violations.add("Modified/deleted file is unverifiable without explicit edit/write: " + relPath + ".");
                continue;
            }
            Integer firstReadOrdinal = firstProvableReadOrdinalByPath.get(relPath);
            if (firstReadOrdinal == null) {
                violations.add("No provable file_read before edit/write for " + relPath
                        + " (earliest edit/write #" + firstWriteOrdinal + ").");
                continue;
            }
            if (firstReadOrdinal >= firstWriteOrdinal) {
                violations.add("file_read for " + relPath + " happened after the earliest edit/write"
                        + " (read #" + firstReadOrdinal + ", edit/write #" + firstWriteOrdinal + ").");
            }
        }
    }

    private static boolean isProvableReadSuccess(String resultJson) {
        if (resultJson == null) {
            return false;
        }
        String trimmed = resultJson.stripLeading();
        return READ_FAILURE_PREFIXES.stream().noneMatch(trimmed::startsWith);
    }

    private static String extractNormalizedPath(Path workspace, String inputJson, String fieldName) {
        try {
            JsonNode root = MAPPER.readTree(inputJson);
            JsonNode value = root.get(fieldName);
            if (value == null || !value.isTextual()) {
                return null;
            }
            return normalizeAgainstWorkspace(workspace, value.asText());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeAgainstWorkspace(Path workspace, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            return null;
        }
        try {
            Path normalizedWorkspace = workspace.toAbsolutePath().normalize();
            Path inputPath = Path.of(rawPath).normalize();
            Path absolutePath = inputPath.isAbsolute()
                    ? inputPath
                    : normalizedWorkspace.resolve(inputPath).normalize();
            if (!absolutePath.startsWith(normalizedWorkspace)) {
                return null;
            }
            return slashPath(normalizedWorkspace.relativize(absolutePath));
        } catch (InvalidPathException ignored) {
            return null;
        }
    }

    private static String normalizeRelativePath(String relPath) {
        try {
            return slashPath(Path.of(relPath).normalize());
        } catch (InvalidPathException ignored) {
            return relPath.replace('\\', '/');
        }
    }

    private static String slashPath(Path path) {
        return path.toString().replace(path.getFileSystem().getSeparator(), "/");
    }
}
