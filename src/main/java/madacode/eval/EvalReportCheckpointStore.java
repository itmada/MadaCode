package madacode.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;

/** Persists case-complete checkpoints and the live root report for an eval run. */
public final class EvalReportCheckpointStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path runDir;
    private final Path htmlOut;
    private final Path jsonOut;
    private final EvalCostEstimator costEstimator;
    private final int plannedCases;
    private final Instant startedAt;

    public EvalReportCheckpointStore(
            Path runDir,
            Path htmlOut,
            Path jsonOut,
            EvalCostEstimator costEstimator,
            int plannedCases) {
        this.runDir = normalize(runDir);
        this.htmlOut = normalize(htmlOut);
        this.jsonOut = normalize(jsonOut);
        this.costEstimator = costEstimator == null ? EvalCostEstimator.none() : costEstimator;
        this.plannedCases = plannedCases;
        this.startedAt = existingStartedAt(this.runDir.resolve("report.json"));
    }

    public void caseCompleted(
            EvalCaseReport report,
            List<EvalCaseReport> completedReports,
            String nextCaseId) {
        Path caseReport = runDir.resolve("cases")
                .resolve(report.id())
                .resolve("case-report.json");
        atomicWrite(caseReport, EvalReportJson.renderCase(report, costEstimator));
        writeRoot(
                completedReports,
                EvalRunProgress.Status.RUNNING,
                nextCaseId,
                null);
        System.out.println("Case report written to " + caseReport);
    }

    public void completed(List<EvalCaseReport> reports) {
        writeRoot(reports, EvalRunProgress.Status.COMPLETED, null, null);
        printTargets();
    }

    public void aborted(List<EvalCaseReport> reports, Throwable failure) {
        writeRoot(
                reports,
                EvalRunProgress.Status.ABORTED,
                null,
                errorMessage(failure));
    }

    private void writeRoot(
            List<EvalCaseReport> reports,
            EvalRunProgress.Status status,
            String currentCaseId,
            String abortDetail) {
        Instant updatedAt = Instant.now();
        EvalRunProgress progress = new EvalRunProgress(
                status,
                plannedCases,
                reports.size(),
                startedAt,
                updatedAt,
                currentCaseId,
                abortDetail);
        String json = EvalReportJson.render(reports, costEstimator, progress);
        String html = EvalReportHtml.render(reports, progress, costEstimator, runDir);

        Path canonicalHtml = runDir.resolve("report.html");
        Path canonicalJson = runDir.resolve("report.json");
        atomicWrite(canonicalHtml, html);
        atomicWrite(canonicalJson, json);
        if (!htmlOut.equals(canonicalHtml)) {
            atomicWrite(htmlOut, html);
        }
        if (!jsonOut.equals(canonicalJson)) {
            atomicWrite(jsonOut, json);
        }
    }

    private void printTargets() {
        System.out.println("可视化报告：" + htmlOut);
        System.out.println("机器报告：" + jsonOut);
        System.out.println("Case 与 Attempt 产物：" + runDir.resolve("cases"));
    }

    static void atomicWrite(Path target, String content) {
        try {
            Path parent = target.toAbsolutePath().normalize().getParent();
            if (parent == null) {
                throw new IOException("report target has no parent: " + target);
            }
            Files.createDirectories(parent);
            Path temporary = parent.resolve("." + target.getFileName() + ".tmp");
            Files.writeString(temporary, content);
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to write eval report: " + target, e);
        }
    }

    private static Instant existingStartedAt(Path reportJson) {
        if (Files.isRegularFile(reportJson)) {
            try {
                EvalReportJson.ReportJson report = MAPPER.readValue(
                        reportJson.toFile(), EvalReportJson.ReportJson.class);
                if (report.run() != null && report.run().startedAt() != null) {
                    return Instant.parse(report.run().startedAt());
                }
            } catch (IOException | RuntimeException ignored) {
                // The case checkpoints remain authoritative when a root snapshot is unreadable.
            }
        }
        return Instant.now();
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private static String errorMessage(Throwable failure) {
        if (failure == null) {
            return "unknown failure";
        }
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
