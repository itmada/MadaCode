package madacode.memory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class MemoryStore {

    static final String INDEX_FILENAME = "MEMORY.md";

    private static final Path DEFAULT_DIR =
            Path.of(System.getProperty("user.home"), ".mada", "memory");

    private final Path root;

    public MemoryStore(Path root) {
        this.root = root;
    }

    public static MemoryStore defaultStore() {
        return new MemoryStore(DEFAULT_DIR);
    }

    public Optional<String> readIndex() {
        return readFile(INDEX_FILENAME);
    }

    public void writeIndex(String content) {
        writeFile(INDEX_FILENAME, content);
    }

    public List<MemoryFile> listAll() {
        ensureDir();
        List<MemoryFile> result = new ArrayList<>();
        try (Stream<Path> files = Files.list(root)) {
            files.filter(f -> f.getFileName().toString().endsWith(".md")
                            && !f.getFileName().toString().equals(INDEX_FILENAME))
                    .forEach(f -> readFile(f.getFileName().toString())
                            .flatMap(raw -> Optional.ofNullable(
                                    MemoryFrontmatter.parse(raw, f)))
                            .ifPresent(result::add));
        } catch (IOException ignored) {
        }
        return result;
    }

    public Optional<MemoryFile> read(String filename) {
        return readFile(filename)
                .flatMap(raw -> Optional.ofNullable(
                        MemoryFrontmatter.parse(raw, root.resolve(filename))));
    }

    public void write(MemoryFile file, String filename) {
        ensureDir();
        writeFile(filename, MemoryFrontmatter.serialize(new MemoryFile(
                file.name(), file.description(), file.type(), file.body(),
                root.resolve(filename))));
        upsertIndex(file, filename);
    }

    private Optional<String> readFile(String filename) {
        Path target = root.resolve(filename);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(target));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void writeFile(String filename, String content) {
        try {
            Files.writeString(root.resolve(filename), content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + filename, e);
        }
    }

    private void ensureDir() {
        try {
            Files.createDirectories(root);
        } catch (IOException ignored) {
        }
    }

    private void upsertIndex(MemoryFile file, String filename) {
        String existing = readIndex().orElse("");
        String line = "- [" + file.name() + "](" + filename + ") — " + file.description();

        if (existing.contains("](" + filename + ")")) {
            // Replace existing line
            String[] existingLines = existing.split("\n");
            StringBuilder sb = new StringBuilder();
            for (String l : existingLines) {
                if (l.contains("](" + filename + ")")) {
                    sb.append(line).append('\n');
                } else {
                    sb.append(l).append('\n');
                }
            }
            writeIndex(sb.toString().stripTrailing() + "\n");
        } else {
            String updated = existing.isBlank() ? line + "\n"
                    : existing.stripTrailing() + "\n" + line + "\n";
            writeIndex(updated);
        }
    }
}
