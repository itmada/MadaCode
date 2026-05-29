package madacode.skill;

import madacode.events.AppEvents;
import madacode.events.DiagnosticEvent;
import madacode.events.EventContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class DiskSkillLoader implements SkillLoader {

    private final Path rootDir;
    private final SkillSource source;

    public DiskSkillLoader(Path rootDir, SkillSource source) {
        this.rootDir = rootDir;
        this.source = source;
    }

    @Override
    public List<Skill> load() {
        if (rootDir == null || !Files.isDirectory(rootDir)) return List.of();

        List<Skill> skills = new ArrayList<>();
        try (Stream<Path> entries = Files.list(rootDir)) {
            for (Path entry : entries.filter(Files::isDirectory).sorted().toList()) {
                Path skillMd = entry.resolve("SKILL.md");
                if (!Files.isRegularFile(skillMd)) continue;

                String content = Files.readString(skillMd, StandardCharsets.UTF_8);
                Skill s = BundledSkillLoader.buildSkill(
                        content, entry.getFileName().toString(),
                        source, skillMd, entry);
                if (s != null) skills.add(s);
            }
        } catch (IOException e) {
            AppEvents.publisher().publish(DiagnosticEvent.warn(
                    EventContext.bootstrap("DiskSkillLoader"),
                    "Failed to scan " + rootDir + ": " + e.getMessage(), e));
        }
        return skills;
    }
}
