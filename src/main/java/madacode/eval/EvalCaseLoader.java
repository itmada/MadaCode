package madacode.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Discovers eval cases on disk under {@code <casesDir>/<case-id>/case.json}.
 *
 * <p>Each case directory holds {@code case.json} (parsed into {@link EvalCase}),
 * a {@code workspace/} directory of starting files, and an executable
 * {@code verify.sh}. The directory name must match the authoritative {@code id}
 * field inside {@code case.json}.
 */
public final class EvalCaseLoader {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path casesDir;

    public EvalCaseLoader(Path casesDir) {
        this.casesDir = casesDir.toAbsolutePath().normalize();
    }

    /** Loads every case under the cases directory, sorted by id for stable reports. */
    public List<LoadedCase> loadAll() {
        if (!Files.isDirectory(casesDir)) {
            throw new IllegalStateException("eval cases directory not found: " + casesDir);
        }
        List<LoadedCase> cases = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        try (var dirs = Files.newDirectoryStream(casesDir, Files::isDirectory)) {
            for (Path dir : dirs) {
                Path normalizedDir = dir.toAbsolutePath().normalize();
                Path caseFile = normalizedDir.resolve("case.json");
                if (!Files.isRegularFile(caseFile)) {
                    continue;
                }
                rejectSymbolicLinks(normalizedDir);
                EvalCase parsed;
                try {
                    parsed = mapper.readValue(caseFile.toFile(), EvalCase.class);
                } catch (IOException e) {
                    throw new UncheckedIOException("failed to parse " + caseFile, e);
                }
                // Resolve derived configuration during preflight, before any runtime or
                // provider is initialized.
                parsed.permissionMode();
                RunBudget.from(parsed);
                if (parsed.capabilities().contains("selftest") && parsed.expectedVerdict() == null) {
                    throw new IllegalArgumentException(
                            "case " + parsed.id() + ": selftest cases must declare expectedVerdict");
                }
                if (!parsed.id().equals(normalizedDir.getFileName().toString())) {
                    throw new IllegalArgumentException(
                            "case id '" + parsed.id() + "' must match directory name '"
                                    + normalizedDir.getFileName() + "'");
                }
                if (!ids.add(parsed.id())) {
                    throw new IllegalArgumentException("duplicate eval case id: " + parsed.id());
                }
                Path workspace = normalizedDir.resolve("workspace");
                Path verify = normalizedDir.resolve("verify.sh");
                if (!Files.isDirectory(workspace)) {
                    throw new IllegalArgumentException("case " + parsed.id() + ": workspace/ is required");
                }
                if (!Files.isRegularFile(verify)) {
                    throw new IllegalArgumentException("case " + parsed.id() + ": verify.sh is required");
                }
                cases.add(new LoadedCase(parsed, normalizedDir, hashTree(normalizedDir)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to scan " + casesDir, e);
        }
        cases.sort((a, b) -> a.evalCase().id().compareTo(b.evalCase().id()));
        return cases;
    }

    /** An eval case paired with the directory it was loaded from (holds workspace/ + verify.sh). */
    public record LoadedCase(EvalCase evalCase, Path directory, String caseHash) {
        public Path workspaceDir() {
            return directory.resolve("workspace").toAbsolutePath().normalize();
        }

        public Path verifyScript() {
            return directory.resolve("verify.sh").toAbsolutePath().normalize();
        }
    }

    private static void rejectSymbolicLinks(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            Path link = walk.filter(Files::isSymbolicLink).findFirst().orElse(null);
            if (link != null) {
                throw new IllegalArgumentException("symbolic links are not allowed in eval cases: " + link);
            }
        }
    }

    private static String hashTree(Path root) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path path : walk.filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                        .toList()) {
                    digest.update(root.relativize(path).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(Files.readAllBytes(path));
                    digest.update((byte) 0);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
