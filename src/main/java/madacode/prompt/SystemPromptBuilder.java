package madacode.prompt;

import madacode.core.session.ConversationSession;
import madacode.longrunning.LongRunningPromptSection;
import madacode.memory.MemoryLoader;
import madacode.skill.Skill;
import madacode.skill.SkillRegistry;
import madacode.tool.Tool;
import madacode.tool.ToolVisibility;
import madacode.tool.VisibleTools;

import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

public class SystemPromptBuilder {

    private static final PromptText PROMPT_TEXT = PromptText.load();

    private final String agentContext;
    private final MemoryLoader memoryLoader;
    private final SkillRegistry skillRegistry;
    private final List<PromptSectionEntry> sections;
    private final Object cacheLock = new Object();
    private volatile PromptCacheEntry cachedPrompt;

    public SystemPromptBuilder(Builder builder) {
        Builder safeBuilder = Objects.requireNonNull(builder, "builder");
        this.agentContext = safeBuilder.agentContext;
        this.memoryLoader = safeBuilder.memoryLoader;
        this.skillRegistry = safeBuilder.skillRegistry;
        this.sections = List.copyOf(safeBuilder.sections);
    }

    public static Builder builder() {
        return new Builder();
    }

    public String build(VisibleTools tools) {
        return build(tools, null, null);
    }

    public String build(VisibleTools tools, Path cwd) {
        return build(tools, cwd, null);
    }

    public String build(VisibleTools tools, Path cwd, ConversationSession session) {
        PromptFingerprint fingerprint = fingerprint(tools, cwd, session);
        PromptCacheEntry cached = cachedPrompt;
        if (cached != null && cached.fingerprint().equals(fingerprint)) {
            return cached.prompt();
        }
        PromptContext ctx = new PromptContext(
                tools == null ? ToolVisibility.empty() : tools,
                cwd,
                session,
                agentContext,
                memoryLoader,
                skillRegistry);
        StringBuilder sb = new StringBuilder();
        for (PromptSectionEntry entry : sections) {
            Optional<String> rendered = entry.section().render(ctx);
            if (rendered.isEmpty() || rendered.get().isBlank()) {
                continue;
            }
            appendSection(sb, entry.title(), rendered.get());
        }
        String prompt = sb.toString();
        synchronized (cacheLock) {
            cachedPrompt = new PromptCacheEntry(fingerprint, prompt);
        }
        return prompt;
    }

    public static VisibleTools visibleToolsForSession(Collection<Tool<?>> tools,
                                                      ConversationSession session) {
        return ToolVisibility.visibleToolsForSession(tools, session);
    }

    private static void appendSection(StringBuilder sb, String title, String body) {
        if (body == null || body.isBlank()) {
            return;
        }
        if (!sb.isEmpty()) {
            sb.append("\n\n");
        }
        if (title != null && !title.isBlank()) {
            sb.append("## ").append(title).append("\n");
            sb.append(body.strip());
        } else {
            sb.append(body);
        }
    }

    private static String bullets(String... items) {
        return bullets(List.of(items));
    }

    private static String bullets(List<String> items) {
        return items.stream()
                .filter(item -> item != null && !item.isBlank())
                .map(item -> "- " + item)
                .collect(Collectors.joining("\n"));
    }

    private record PromptSectionEntry(String title, PromptSection section) {
        private PromptSectionEntry {
            Objects.requireNonNull(section, "section");
        }
    }

    private record PromptCacheEntry(PromptFingerprint fingerprint, String prompt) {
    }

    private record PromptFingerprint(
            List<String> toolNames,
            String workingDirectory,
            String sessionId,
            int messageCount,
            boolean planMode,
            String permissionMode,
            String workflowMode,
            String longRunningStage,
            boolean longRunningWorkerSession,
            String longRunningTaskId,
            String longRunningTaskDirectory,
            String longRunningTaskTitle,
            String longRunningReason,
            String longRunningPlanSummary,
            String loadedDeferredTools,
            String agentContext) {
    }

    private PromptFingerprint fingerprint(VisibleTools tools, Path cwd, ConversationSession session) {
        VisibleTools visibleTools = tools == null ? ToolVisibility.empty() : tools;
        if (session == null) {
            return new PromptFingerprint(
                    visibleTools.stream().map(Tool::name).toList(),
                    cwd == null ? null : cwd.toAbsolutePath().normalize().toString(),
                    null,
                    0,
                    false,
                    null,
                    null,
                    null,
                    false,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "",
                    agentContext);
        }
        return new PromptFingerprint(
                visibleTools.stream().map(Tool::name).toList(),
                cwd == null ? null : cwd.toAbsolutePath().normalize().toString(),
                session.sessionId(),
                session.transcriptMessages().size(),
                session.isPlanMode(),
                session.permissionMode().name(),
                session.workflowMode().name(),
                session.longRunningStage() == null ? null : session.longRunningStage().name(),
                session.isLongRunningWorkerSession(),
                session.longRunningTaskId(),
                session.longRunningTaskDirectory(),
                session.longRunningTaskTitle(),
                session.longRunningReason(),
                session.longRunningPlanSummary(),
                String.join(",", session.loadedDeferredTools().stream().sorted().toList()),
                agentContext);
    }

    public static final class Builder {
        private String agentContext;
        private MemoryLoader memoryLoader;
        private SkillRegistry skillRegistry;
        private final List<PromptSectionEntry> sections = new ArrayList<>();

        private Builder() {
            sections.addAll(defaultSections());
        }

        public Builder agentContext(String agentContext) {
            this.agentContext = agentContext;
            return this;
        }

        public Builder memoryLoader(MemoryLoader memoryLoader) {
            this.memoryLoader = memoryLoader;
            return this;
        }

        public Builder skillRegistry(SkillRegistry skillRegistry) {
            this.skillRegistry = skillRegistry;
            return this;
        }

        public Builder clearSections() {
            sections.clear();
            return this;
        }

        public Builder addSection(String title, PromptSection section) {
            sections.add(new PromptSectionEntry(title, section));
            return this;
        }

        public SystemPromptBuilder build() {
            return new SystemPromptBuilder(this);
        }

        private static List<PromptSectionEntry> defaultSections() {
            return List.of(
                    new PromptSectionEntry("Identity", new IdentitySection()),
                    new PromptSectionEntry("System", new SystemSection()),
                    new PromptSectionEntry("Runtime Mode", new RuntimeModeSection()),
                    new PromptSectionEntry("Plan Mode", new PlanModeSection()),
                    new PromptSectionEntry("Environment", new EnvironmentSection()),
                    new PromptSectionEntry("Working In Codebases", new CodebaseSection()),
                    new PromptSectionEntry("Tools", new ToolsSection()),
                    new PromptSectionEntry("Executing Actions", new ActionsSection()),
                    new PromptSectionEntry("Communication", new CommunicationSection()),
                    new PromptSectionEntry("Final Responses", new FinalResponseSection()),
                    new PromptSectionEntry("Skills", new SkillsSection()),
                    new PromptSectionEntry("Project & user context", new ProjectContextSection()),
                    new PromptSectionEntry("Long-Running Workflow", new LongRunningPromptSection()),
                    new PromptSectionEntry(null, new AgentContextSection()));
        }
    }

    private static final class IdentitySection implements PromptSection {
        @Override
        public Optional<String> render(PromptContext ctx) {
            return Optional.of(PROMPT_TEXT.text("identity"));
        }
    }

    private static final class SystemSection implements PromptSection {
        @Override
        public Optional<String> render(PromptContext ctx) {
            return Optional.of(PROMPT_TEXT.bullets("system"));
        }
    }

    private static final class RuntimeModeSection implements PromptSection {
        @Override
        public Optional<String> render(PromptContext ctx) {
            ConversationSession session = ctx.session();
            if (session == null) {
                return Optional.empty();
            }
            List<String> items = new ArrayList<>();
            if (session.isPlanMode()) {
                items.add(PROMPT_TEXT.text("runtime_mode.plan"));
            } else if (session.isLongRunningWorkerSession()) {
                items.add(PROMPT_TEXT.text("runtime_mode.long_running_worker"));
                if (session.longRunningStage() != null) {
                    items.add("Long-running stage: " + session.longRunningStage().name() + ".");
                }
            } else if (session.workflowMode() == madacode.core.session.SessionMode.LONG_RUNNING) {
                items.add(PROMPT_TEXT.text("runtime_mode.long_running"));
                if (session.longRunningStage() != null) {
                    items.add("Long-running stage: " + session.longRunningStage().name() + ".");
                }
            } else {
                items.add(PROMPT_TEXT.text("runtime_mode.default"));
            }
            return Optional.of(bullets(items));
        }
    }

    private static final class PlanModeSection implements PromptSection {
        @Override
        public Optional<String> render(PromptContext ctx) {
            ConversationSession session = ctx.session();
            if (session == null || !session.isPlanMode()) {
                return Optional.empty();
            }
            return Optional.of(PROMPT_TEXT.bullets("plan_mode"));
        }
    }

    private static final class EnvironmentSection implements PromptSection {
        @Override
        public Optional<String> render(PromptContext ctx) {
            if (ctx.workingDirectory() == null) {
                return Optional.empty();
            }
            Path normalized = ctx.workingDirectory().toAbsolutePath().normalize();
            return Optional.of(PROMPT_TEXT.bullets("environment", "{cwd}", normalized.toString()));
        }
    }

    private static final class CodebaseSection implements PromptSection {
        @Override
        public Optional<String> render(PromptContext ctx) {
            return Optional.of(PROMPT_TEXT.bullets("codebase"));
        }
    }

    private static final class ToolsSection implements PromptSection {
        @Override
        public Optional<String> render(PromptContext ctx) {
            VisibleTools visibleTools = ctx.visibleTools();
            String names = visibleTools.stream().map(Tool::name).collect(Collectors.joining(", "));
            Set<String> toolNames = visibleTools.names();
            List<String> items = new ArrayList<>();
            items.add("Available tools: " + names);
            items.addAll(PROMPT_TEXT.lines("tools.base"));
            if (toolNames.contains("file_read")) {
                items.add(PROMPT_TEXT.text("tools.file_read"));
            }
            if (toolNames.contains("file_edit")) {
                items.add(PROMPT_TEXT.text("tools.file_edit"));
            }
            if (toolNames.contains("file_write")) {
                items.add(PROMPT_TEXT.text("tools.file_write"));
            }
            if (toolNames.contains("glob")) {
                items.add(PROMPT_TEXT.text("tools.glob"));
            }
            if (toolNames.contains("grep")) {
                items.add(PROMPT_TEXT.text("tools.grep"));
            }
            if (toolNames.contains("update_plan")) {
                items.add(PROMPT_TEXT.text("tools.update_plan"));
            }
            if (toolNames.contains("ask_user_question")) {
                items.add(PROMPT_TEXT.text("tools.ask_user_question"));
            }
            if (toolNames.contains("add_provider")) {
                items.add(PROMPT_TEXT.text("tools.add_provider"));
            }
            if (toolNames.contains("tool_search")) {
                items.add(PROMPT_TEXT.text("tools.tool_search"));
            }
            if (toolNames.contains("bash")) {
                items.add(PROMPT_TEXT.text("tools.bash"));
            }
            items.add(PROMPT_TEXT.text("tools.parallel"));
            return Optional.of(bullets(items));
        }
    }

    private static final class ActionsSection implements PromptSection {
        @Override
        public Optional<String> render(PromptContext ctx) {
            return Optional.of(PROMPT_TEXT.bullets("actions"));
        }
    }

    private static final class CommunicationSection implements PromptSection {
        @Override
        public Optional<String> render(PromptContext ctx) {
            return Optional.of(PROMPT_TEXT.bullets("communication"));
        }
    }

    private static final class FinalResponseSection implements PromptSection {
        @Override
        public Optional<String> render(PromptContext ctx) {
            return Optional.of(PROMPT_TEXT.bullets("final_responses"));
        }
    }

    private static final class SkillsSection implements PromptSection {
        @Override
        public Optional<String> render(PromptContext ctx) {
            boolean hasSkillTool = ctx.visibleTools().names().contains("skill");
            if (!hasSkillTool || ctx.skillRegistry() == null) {
                return Optional.empty();
            }
            List<Skill> enabledSkills = ctx.skillRegistry().enabled();
            if (enabledSkills.isEmpty()) {
                return Optional.empty();
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Invoke via skill(skill=\"<name>\", task=\"...\")\n");
            for (Skill skill : enabledSkills) {
                sb.append("- **").append(skill.name()).append("**");
                if (!skill.description().isBlank()) {
                    sb.append(": ").append(skill.description());
                }
                if (!skill.whenToUse().isBlank()) {
                    sb.append(" (").append(skill.whenToUse()).append(")");
                }
                sb.append("\n");
            }
            return Optional.of(sb.toString().stripTrailing());
        }
    }

    private static final class ProjectContextSection implements PromptSection {
        @Override
        public Optional<String> render(PromptContext ctx) {
            if (ctx.memoryLoader() == null || ctx.workingDirectory() == null) {
                return Optional.empty();
            }
            return ctx.memoryLoader().renderForSystemPrompt(ctx.workingDirectory());
        }
    }

    private static final class AgentContextSection implements PromptSection {
        @Override
        public Optional<String> render(PromptContext ctx) {
            return Optional.ofNullable(ctx.agentContext());
        }
    }

    private record PromptText(Properties properties) {
        private static PromptText load() {
            Properties properties = new Properties();
            try (InputStream in = SystemPromptBuilder.class.getResourceAsStream(
                    "/prompts/system-prompt-sections.properties")) {
                if (in == null) {
                    throw new IllegalStateException("Missing system prompt section resources");
                }
                properties.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return new PromptText(properties);
        }

        String text(String key) {
            String value = properties.getProperty(key);
            if (value == null) {
                throw new IllegalStateException("Missing prompt text: " + key);
            }
            return value;
        }

        List<String> lines(String key) {
            return List.of(text(key).split("\\n", -1));
        }

        String bullets(String key) {
            return SystemPromptBuilder.bullets(lines(key));
        }

        String bullets(String key, String target, String replacement) {
            return SystemPromptBuilder.bullets(text(key).replace(target, replacement).lines().toList());
        }
    }
}
