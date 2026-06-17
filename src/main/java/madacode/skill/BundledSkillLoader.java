package madacode.skill;

import madacode.events.AppEventPublisher;
import madacode.events.DiagnosticEvent;
import madacode.events.EventContext;
import madacode.tool.ToolNameCanonicalizer;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Loads bundled skills from {@code src/main/resources/skills/} inside the jar.
 *
 * <p>Each subdirectory with a {@code SKILL.md} becomes a skill. The directory
 * name is used as the skill name if the frontmatter doesn't specify one.
 */
public final class BundledSkillLoader implements SkillLoader {

    private static final String BUNDLED_DIR = "skills";
    private final AppEventPublisher publisher;

    public BundledSkillLoader(AppEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public List<Skill> load() {
        try {
            return loadFromClasspath(publisher);
        } catch (IOException | URISyntaxException e) {
            publisher.publish(DiagnosticEvent.warn(
                    EventContext.bootstrap("BundledSkillLoader"),
                    "Failed to load bundled skills: " + e.getMessage(), e));
            return List.of();
        }
    }

    private static List<Skill> loadFromClasspath(AppEventPublisher publisher)
            throws IOException, URISyntaxException {
        var url = BundledSkillLoader.class.getClassLoader().getResource(BUNDLED_DIR);
        if (url == null) return List.of();

        if ("jar".equals(url.getProtocol())) {
            return loadFromJar(url, publisher);
        }
        return loadFromDirectory(Path.of(url.toURI()), publisher);
    }

    private static List<Skill> loadFromJar(java.net.URL dirUrl, AppEventPublisher publisher)
            throws IOException {
        List<Skill> skills = new ArrayList<>();
        String jarPath = dirUrl.getPath().substring(5, dirUrl.getPath().indexOf('!'));
        try (FileSystem fs = FileSystems.newFileSystem(
                URI.create("jar:file:" + jarPath), Collections.emptyMap())) {
            Path skillsRoot = fs.getPath(BUNDLED_DIR);
            if (!Files.isDirectory(skillsRoot)) return List.of();
            skills.addAll(loadFromDirectory(skillsRoot, publisher));
        }
        return skills;
    }

    private static List<Skill> loadFromDirectory(Path dir, AppEventPublisher publisher)
            throws IOException {
        List<Skill> skills = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.filter(Files::isDirectory).sorted().toList()) {
                Path skillMd = entry.resolve("SKILL.md");
                if (!Files.isRegularFile(skillMd)) continue;

                String content = Files.readString(skillMd, StandardCharsets.UTF_8);
                Skill s = buildSkill(content, entry.getFileName().toString(),
                        SkillSource.BUNDLED, skillMd, entry, publisher);
                if (s != null) skills.add(s);
            }
        }
        return skills;
    }

    static Skill buildSkill(String content, String dirName,
                            SkillSource source, Path mdPath, Path dirPath,
                            AppEventPublisher publisher) {
        SkillFrontmatterParser.Result parsed = SkillFrontmatterParser.parse(content);
        for (String w : parsed.warnings()) {
            publisher.publish(DiagnosticEvent.warn(
                    EventContext.bootstrap("BundledSkillLoader"),
                    mdPath + ": " + w));
        }

        Map<String, Object> fm = parsed.frontmatter();
        if (fm.containsKey("max_tool_calls")) {
            publisher.publish(DiagnosticEvent.warn(
                    EventContext.bootstrap("BundledSkillLoader"),
                    mdPath + ": max_tool_calls is no longer supported; skipping skill"));
            return null;
        }

        String name = SkillFrontmatterParser.stringField(fm, "name");
        if (name == null || name.isBlank()) name = dirName;

        String desc = SkillFrontmatterParser.stringField(fm, "description");
        if (desc == null) desc = "";

        String when = SkillFrontmatterParser.stringField(fm, "when_to_use");
        if (when == null) when = "";

        List<String> tags = SkillFrontmatterParser.stringListField(fm, "tags");

        String mode = SkillFrontmatterParser.stringField(fm, "mode");
        if (mode == null) mode = "inline";

        boolean allowedToolsSpecified = fm.containsKey("allowed_tools");
        List<String> allowed = canonicalize(
                SkillFrontmatterParser.stringListField(fm, "allowed_tools"));
        List<String> disallowed = canonicalize(
                SkillFrontmatterParser.stringListField(fm, "disallowed_tools"));
        Integer maxIter = optionalPositiveInt(fm, "max_iterations", mdPath, publisher);

        return new Skill(name, desc, when, tags, source, parsed.body(),
                mdPath, dirPath, mode, allowed, disallowed, allowedToolsSpecified, maxIter);
    }

    private static Integer optionalPositiveInt(
            Map<String, Object> fm,
            String field,
            Path source,
            AppEventPublisher publisher) {
        if (!fm.containsKey(field)) {
            return null;
        }
        Object raw = fm.get(field);
        if (raw instanceof String s) {
            try {
                int value = Integer.parseInt(s);
                if (value > 0) {
                    return value;
                }
            } catch (NumberFormatException ignored) {
                // Warn below with the original value.
            }
        }
        publisher.publish(DiagnosticEvent.warn(
                EventContext.bootstrap("BundledSkillLoader"),
                source + ": " + field + " must be a positive integer; leaving unbounded"));
        return null;
    }

    private static List<String> canonicalize(List<String> names) {
        if (names == null) {
            return List.of();
        }
        return names.stream()
                .map(ToolNameCanonicalizer::canonicalize)
                .filter(name -> name != null && !name.isBlank())
                .toList();
    }
}
