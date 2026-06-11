package madacode.agent;

import madacode.events.AppEventPublisher;
import madacode.events.DiagnosticEvent;
import madacode.events.EventContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Loads agents from a directory containing {@code *.md} files
 * (one file per agent). Missing or non-directory paths return empty.
 *
 * <p>Fallback name (used when frontmatter omits {@code name:}) is the
 * filename with the {@code .md} suffix stripped.
 */
public final class DiskAgentLoader implements AgentLoader {

    private final Path rootDir;
    private final AppEventPublisher publisher;

    public DiskAgentLoader(Path rootDir, AppEventPublisher publisher) {
        this.rootDir = rootDir;
        this.publisher = publisher;
    }

    @Override
    public List<AgentDefinition> load() {
        if (rootDir == null || !Files.isDirectory(rootDir)) {
            return List.of();
        }

        List<AgentDefinition> result = new ArrayList<>();
        try (Stream<Path> entries = Files.list(rootDir)) {
            List<Path> mdFiles = entries
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted()
                    .toList();
            for (Path file : mdFiles) {
                try {
                    String content = Files.readString(file, StandardCharsets.UTF_8);
                    String fileName = file.getFileName().toString();
                    String fallback = fileName.substring(0, fileName.length() - ".md".length());
                    Optional<AgentDefinition> def =
                            AgentDefinitionParser.parse(content, fallback, file, publisher);
                    def.ifPresent(result::add);
                } catch (IOException e) {
                    publisher.publish(DiagnosticEvent.warn(
                            EventContext.bootstrap("DiskAgentLoader"),
                            "failed to read " + file + ": " + e.getMessage(), e));
                }
            }
        } catch (IOException e) {
            publisher.publish(DiagnosticEvent.warn(
                    EventContext.bootstrap("DiskAgentLoader"),
                    "failed to scan " + rootDir + ": " + e.getMessage(), e));
        }
        return result;
    }
}
