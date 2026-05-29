package madacode.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class MadaMdLoader {

    private static final Path USER_GLOBAL = Path.of(
            System.getProperty("user.home"), ".mada", "MADA.md");

    public List<LoadedFile> load(Path cwd) {
        List<LoadedFile> result = new ArrayList<>();

        // 1. User-global: ~/.mada/MADA.md
        if (Files.isRegularFile(USER_GLOBAL)) {
            readIfPresent(USER_GLOBAL, "user-global", result);
        }

        // 2. Project-root: walk up from cwd to find the nearest MADA.md
        Path projectRoot = walkUp(cwd);
        if (projectRoot != null && !projectRoot.equals(USER_GLOBAL)) {
            readIfPresent(projectRoot, "project-root", result);
        }

        // 3. Cwd-level: if different from project root and user global
        Path cwdFile = cwd.resolve("MADA.md");
        if (Files.isRegularFile(cwdFile)
                && !cwdFile.toAbsolutePath().normalize().equals(projectRoot)
                && !cwdFile.toAbsolutePath().normalize().equals(USER_GLOBAL.toAbsolutePath().normalize())) {
            readIfPresent(cwdFile, "cwd", result);
        }

        return result;
    }

    public record LoadedFile(Path path, String source, String content) {}

    private Path walkUp(Path start) {
        Path current = start.toAbsolutePath().normalize().getParent();
        while (current != null) {
            Path candidate = current.resolve("MADA.md");
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
            Path parent = current.getParent();
            if (parent == null || parent.equals(current)) {
                break;
            }
            current = parent;
        }
        return null;
    }

    private void readIfPresent(Path path, String source, List<LoadedFile> result) {
        try {
            String content = Files.readString(path);
            if (!content.isBlank()) {
                result.add(new LoadedFile(path, source, content));
            }
        } catch (IOException ignored) {
        }
    }
}
