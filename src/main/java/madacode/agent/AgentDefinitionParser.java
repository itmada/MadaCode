package madacode.agent;

import madacode.events.AppEventPublisher;
import madacode.events.DiagnosticEvent;
import madacode.events.EventContext;
import madacode.skill.SkillFrontmatterParser;
import madacode.tool.ToolNameCanonicalizer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Parses an agent definition from markdown with YAML frontmatter.
 *
 * <p>Frontmatter schema:
 * <pre>
 * ---
 * name: explorer
 * description: Explores files ...
 * when_to_use: Finding files by ...
 * allowed_tools: [file_read, glob, grep]
 * disallowed_tools: [agent, bash]
 * max_iterations: 6
 * ---
 * (body - becomes the systemPrompt)
 * </pre>
 *
 * <p>{@code max_iterations} is optional. Missing or invalid values leave the
 * agent unbounded; explicit positive values cap model/tool iterations.
 * Returns {@link Optional#empty()} only when the body is blank or
 * construction otherwise fails.
 */
public final class AgentDefinitionParser {

    private AgentDefinitionParser() {}

    public static Optional<AgentDefinition> parse(
            String content,
            String fallbackName,
            Path source,
            AppEventPublisher publisher) {

        SkillFrontmatterParser.Result parsed = SkillFrontmatterParser.parse(content);
        for (String warning : parsed.warnings()) {
            warn(publisher, source + ": " + warning);
        }
        Map<String, Object> fm = parsed.frontmatter();
        if (fm.containsKey("max_tool_calls")) {
            warn(publisher, source + ": max_tool_calls is no longer supported; remove this field");
            return Optional.empty();
        }

        String name = SkillFrontmatterParser.stringField(fm, "name");
        if (name == null || name.isBlank()) {
            name = fallbackName;
        }

        String body = parsed.body();
        if (body == null || body.isBlank()) {
            warn(publisher, source + ": empty systemPrompt body, skipping");
            return Optional.empty();
        }

        String desc = orEmpty(SkillFrontmatterParser.stringField(fm, "description"));
        String when = orEmpty(SkillFrontmatterParser.stringField(fm, "when_to_use"));

        boolean allowedToolsSpecified = fm.containsKey("allowed_tools");
        List<String> allowed = canonicalize(
                SkillFrontmatterParser.stringListField(fm, "allowed_tools"));
        List<String> disallowed = canonicalize(
                SkillFrontmatterParser.stringListField(fm, "disallowed_tools"));

        Integer maxIter = optionalPositiveInt(fm, "max_iterations", source, publisher);

        try {
            return Optional.of(new AgentDefinition(
                    name, desc, when, body,
                    Set.copyOf(allowed), Set.copyOf(disallowed),
                    allowedToolsSpecified, maxIter));
        } catch (IllegalArgumentException e) {
            publisher.publish(DiagnosticEvent.warn(
                    EventContext.bootstrap("AgentDefinitionParser"),
                    source + ": " + e.getMessage() + ", skipping", e));
            return Optional.empty();
        }
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
        warn(publisher, source + ": " + field + " must be a positive integer; leaving unbounded");
        return null;
    }

    private static void warn(AppEventPublisher publisher, String message) {
        publisher.publish(DiagnosticEvent.warn(
                EventContext.bootstrap("AgentDefinitionParser"), message));
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
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
