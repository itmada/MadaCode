package madacode.eval;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Filesystem implementation of per-attempt artifact persistence. */
public final class FileAttemptArtifactWriter implements AttemptArtifactWriter {

    private static final int MAX_RESULT_JSON_CHARS = 256 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path runDir;

    public FileAttemptArtifactWriter(Path runDir) {
        this.runDir = runDir;
    }

    @Override
    public AttemptArtifacts write(
            EvalCase evalCase,
            int attemptNumber,
            AttemptEvidence evidence,
            EvalResult result) {
        String caseDirName = sanitize(evalCase.id());
        Path attemptDir = runDir.resolve(caseDirName).resolve("attempt-" + attemptNumber);
        List<String> files = List.of(
                relative(attemptDir.resolve("trace.json")),
                relative(attemptDir.resolve("verify.txt")),
                relative(attemptDir.resolve("result.json")));
        List<String> warnings = new ArrayList<>();
        try {
            Files.createDirectories(attemptDir);
        } catch (IOException e) {
            return new AttemptArtifacts(
                    relative(attemptDir),
                    files,
                    List.of("failed to create artifact directory: " + e.getMessage()));
        }

        writeFile(
                attemptDir.resolve("trace.json"),
                json(traceJson(evidence == null ? null : evidence.trace())),
                warnings);
        writeFile(
                attemptDir.resolve("verify.txt"),
                verifyText(evidence == null ? null : evidence.verifyOutcome()),
                warnings);

        AttemptArtifacts artifacts = new AttemptArtifacts(relative(attemptDir), files, warnings);
        writeFile(
                attemptDir.resolve("result.json"),
                json(EvalReportJson.attemptJson(result.withArtifacts(artifacts))),
                warnings);
        return new AttemptArtifacts(relative(attemptDir), files, warnings);
    }

    private void writeFile(Path path, String content, List<String> warnings) {
        try {
            Files.writeString(path, content);
        } catch (IOException e) {
            warnings.add("failed to write " + relative(path) + ": " + e.getMessage());
        }
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value) + "\n";
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("failed to render attempt artifact JSON", e);
        }
    }

    private static TraceJson traceJson(ExecutionTrace trace) {
        if (trace == null) {
            return new TraceJson(
                    false,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    "",
                    EvalReportJson.metricsJson(RunMetrics.ZERO));
        }
        return new TraceJson(
                true,
                trace.invocations().stream().map(FileAttemptArtifactWriter::invocationJson).toList(),
                trace.fileEffects(),
                trace.userTurns(),
                trace.assistantTurns(),
                trace.finalText(),
                EvalReportJson.metricsJson(trace.metrics()));
    }

    private static InvocationJson invocationJson(ToolInvocation invocation) {
        String resultJson = invocation.resultJson();
        boolean truncated = resultJson.length() > MAX_RESULT_JSON_CHARS;
        return new InvocationJson(
                invocation.name(),
                invocation.inputJson(),
                truncated ? resultJson.substring(0, MAX_RESULT_JSON_CHARS) : resultJson,
                truncated,
                resultJson.length(),
                invocation.accessEvidence(),
                invocation.phase().name(),
                invocation.ordinal());
    }

    private static String verifyText(EvalExecutionEnvironment.VerifyOutcome outcome) {
        if (outcome == null) {
            return "status=NOT_RUN\nexitCode=-1\n\n";
        }
        return "status=" + outcome.status()
                + "\nexitCode=" + outcome.exitCode()
                + "\n\n" + outcome.output();
    }

    private String relative(Path path) {
        return runDir.relativize(path)
                .toString()
                .replace(java.io.File.separatorChar, '/');
    }

    private static String sanitize(String value) {
        String sanitized = (value == null ? "" : value).replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "case" : sanitized;
    }

    private record TraceJson(
            boolean available,
            List<InvocationJson> invocations,
            List<TouchedFile> fileEffects,
            List<String> userTurns,
            List<String> assistantTurns,
            String finalText,
            EvalReportJson.MetricsJson metrics) {
    }

    private record InvocationJson(
            String name,
            String inputJson,
            String resultJson,
            boolean resultTruncated,
            int originalResultChars,
            List<madacode.core.model.ToolAccessEvidence> accessEvidence,
            String phase,
            int ordinal) {
    }
}
