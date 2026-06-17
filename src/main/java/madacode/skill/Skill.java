package madacode.skill;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record Skill(
        String name,
        String description,
        String whenToUse,
        List<String> tags,
        SkillSource source,
        String body,
        Path path,
        Path baseDir,
        String mode,                  // "inline" (default) or "fork"
        List<String> allowedTools,
        List<String> disallowedTools,
        boolean allowedToolsSpecified,
        Integer maxIterations) {

    public Skill {
        Objects.requireNonNull(name, "name");
        description = Objects.requireNonNullElse(description, "");
        whenToUse = Objects.requireNonNullElse(whenToUse, "");
        tags = tags == null ? List.of() : List.copyOf(tags);
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(path, "path");
        baseDir = baseDir == null ? path.getParent() : baseDir;
        mode = mode == null || mode.isBlank() ? "inline" : mode;
        allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        disallowedTools = disallowedTools == null ? List.of() : List.copyOf(disallowedTools);
        if (maxIterations != null && maxIterations <= 0) {
            throw new IllegalArgumentException("maxIterations must be positive, was " + maxIterations);
        }
    }
}
