package madacode.agent;

import madacode.events.AppEvents;
import madacode.events.DiagnosticEvent;
import madacode.events.EventContext;
import madacode.permission.PermissionMode;
import madacode.skill.SkillFrontmatterParser;
import madacode.util.ToolNameNormalizer;

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
 * max_tool_calls: 20
 * ---
 * (body - becomes the systemPrompt)
 * </pre>
 *
 * <p>Invalid values (e.g. {@code max_iterations <= 0}) are sanitized to
 * defaults with a stderr warning, matching {@code Skill} behaviour.
 * Returns {@link Optional#empty()} only when the body is blank or
 * construction otherwise fails.
 */
public final class AgentDefinitionParser {

    private static final int DEFAULT_MAX_ITERATIONS = 15;
    private static final int DEFAULT_MAX_TOOL_CALLS = 50;

    private AgentDefinitionParser() {}

    public static Optional<AgentDefinition> parse(
            String content, String fallbackName, Path source) {

        SkillFrontmatterParser.Result parsed = SkillFrontmatterParser.parse(content);
        for (String warning : parsed.warnings()) {
            warn(source + ": " + warning);
        }
        Map<String, Object> fm = parsed.frontmatter();

        String name = SkillFrontmatterParser.stringField(fm, "name");
        if (name == null || name.isBlank()) {
            name = fallbackName;
        }

        String body = parsed.body();
        if (body == null || body.isBlank()) {
            warn(source + ": empty systemPrompt body, skipping");
            return Optional.empty();
        }

        String desc = orEmpty(SkillFrontmatterParser.stringField(fm, "description"));
        String when = orEmpty(SkillFrontmatterParser.stringField(fm, "when_to_use"));

        List<String> allowed = ToolNameNormalizer.normalize(
                SkillFrontmatterParser.stringListField(fm, "allowed_tools"));
        List<String> disallowed = ToolNameNormalizer.normalize(
                SkillFrontmatterParser.stringListField(fm, "disallowed_tools"));

        int maxIter = sanitizePositive(
                SkillFrontmatterParser.intField(fm, "max_iterations", DEFAULT_MAX_ITERATIONS),
                DEFAULT_MAX_ITERATIONS, "max_iterations", source);
        int maxCalls = sanitizePositive(
                SkillFrontmatterParser.intField(fm, "max_tool_calls", DEFAULT_MAX_TOOL_CALLS),
                DEFAULT_MAX_TOOL_CALLS, "max_tool_calls", source);

        try {
            return Optional.of(new AgentDefinition(
                    name, desc, when, body,
                    Set.copyOf(allowed), Set.copyOf(disallowed),
                    maxIter, maxCalls, PermissionMode.ACCEPT_EDITS));
        } catch (IllegalArgumentException e) {
            AppEvents.publisher().publish(DiagnosticEvent.warn(
                    EventContext.bootstrap("AgentDefinitionParser"),
                    source + ": " + e.getMessage() + ", skipping", e));
            return Optional.empty();
        }
    }

    private static int sanitizePositive(int value, int fallback, String field, Path source) {
        if (value <= 0) {
            warn(source + ": " + field + " must be > 0, using default " + fallback);
            return fallback;
        }
        return value;
    }

    private static void warn(String message) {
        AppEvents.publisher().publish(DiagnosticEvent.warn(
                EventContext.bootstrap("AgentDefinitionParser"), message));
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
