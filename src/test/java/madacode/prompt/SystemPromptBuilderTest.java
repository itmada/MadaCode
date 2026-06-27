package madacode.prompt;

import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.memory.MemoryLoader;
import madacode.skill.Skill;
import madacode.skill.SkillLoader;
import madacode.skill.SkillRegistry;
import madacode.skill.SkillSource;
import madacode.skill.SkillStateStore;
import madacode.tool.BashTool;
import madacode.tool.FileReadTool;
import madacode.tool.SkillTool;
import madacode.tool.ToolRegistry;
import madacode.tool.ToolVisibility;
import madacode.tool.VisibleTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Structural tests for {@link SystemPromptBuilder}. They check section presence,
 * order, conditional inclusion, and placeholder substitution — but never the
 * exact wording of any section, so prompt copy can evolve without breaking tests.
 */
class SystemPromptBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultSessionEmitsAlwaysOnSectionsInOrderAndOmitsConditionalOnes() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BashTool());
        registry.register(new FileReadTool());

        ConversationSession session = new ConversationSession(tempDir);
        VisibleTools visibleTools = ToolVisibility.visibleToolsForSession(registry.tools(), session);

        String prompt = SystemPromptBuilder.builder().build().build(visibleTools, tempDir, session);

        assertSectionsInOrder(prompt,
                "## Identity",
                "## System",
                "## Runtime Mode",
                "## Environment",
                "## Working In Codebases",
                "## Tools",
                "## Executing Actions",
                "## Communication",
                "## Final Responses");
        assertAll("conditional sections must stay absent",
                () -> assertFalse(prompt.contains("## Plan Mode"), "plan mode is off"),
                () -> assertFalse(prompt.contains("## Long-Running Workflow"), "not in long-running mode"),
                () -> assertFalse(prompt.contains("## Skills"), "no skill tool loaded"),
                () -> assertFalse(prompt.contains("## Project & user context"), "no memory loader"));
        assertTrue(prompt.contains(tempDir.toAbsolutePath().normalize().toString()),
                "Environment section must include the working directory");
        assertTrue(prompt.contains("already run with this as their working directory"),
                "Environment section must tell the model that local tools already run from cwd");
        assertTrue(prompt.contains("do not prefix commands with cd {cwd} &&"),
                "Bash guidance must discourage redundant cd into cwd");
        assertTrue(prompt.contains("prefer paths relative to that directory when the tool schema allows it"),
                "Environment guidance must distinguish user-facing paths from tool input paths");
        assertTrue(prompt.contains("For files inside the primary working directory, pass a relative path"),
                "file_read guidance must prefer relative paths inside cwd");
        assertTrue(prompt.contains("bash") && prompt.contains("file_read"),
                "Tools section must list the visible tools");
    }

    @Test
    void longRunningInterruptSessionIncludesWorkflowSkillsMemoryAndAgentFooter() throws Exception {
        Path projectDir = tempDir.resolve("project");
        java.nio.file.Files.createDirectories(projectDir);

        MemoryLoader memoryLoader = new StubMemoryLoader("<agents-md>project</agents-md>");
        Skill skill = new Skill(
                "review",
                "Review current changes",
                "Use when reviewing a diff",
                List.of(),
                SkillSource.PROJECT,
                "body",
                projectDir.resolve("review/SKILL.md"),
                projectDir.resolve("review"),
                "inline",
                List.of(),
                List.of(),
                true,
                null);
        SkillRegistry skillRegistry = new SkillRegistry(
                new SkillStateStore(tempDir.resolve("skills.json")),
                fixedLoader(skill));
        skillRegistry.reload();

        ToolRegistry registry = new ToolRegistry();
        registry.register(new BashTool());
        registry.register(new FileReadTool());
        registry.register(new SkillTool(skillRegistry,
                new madacode.agent.AgentRunner(registry, (messages, systemPrompt, tools, sink, cancellationToken) -> null,
                        madacode.permission.PermissionGate.permissive())));

        ConversationSession session = new ConversationSession(projectDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.INTERRUPT);
        session.setLongRunningTaskId("task-7");
        session.setLongRunningTaskDirectory(projectDir.resolve(".mada/tasks/task-7").toString());
        session.loadDeferredTool("skill");

        VisibleTools visibleTools = ToolVisibility.visibleToolsForSession(registry.tools(), session);
        SystemPromptBuilder builder = SystemPromptBuilder.builder()
                .memoryLoader(memoryLoader)
                .skillRegistry(skillRegistry)
                .agentContext("Agent footer")
                .build();

        String prompt = builder.build(visibleTools, projectDir, session);

        assertSectionsInOrder(prompt,
                "## Identity",
                "## Runtime Mode",
                "## Tools",
                "## Skills",
                "## Project & user context",
                "## Long-Running Workflow");
        assertTrue(prompt.contains("review"), "Skills section must list the enabled skill");
        assertTrue(prompt.contains("<agents-md>project</agents-md>"),
                "Project & user context must inject the memory loader output");
        assertTrue(prompt.contains("task-7"), "Long-running section must surface the active task id");
        assertTrue(prompt.contains(".mada/tasks/task-7"), "Long-running section must surface the task directory");
        assertTrue(prompt.stripTrailing().endsWith("Agent footer"),
                "Agent context must appear as the final, untitled footer");
        assertFalse(prompt.contains("## Plan Mode"), "Plan mode and long-running are mutually exclusive");
    }

    @Test
    void planModeAddsPlanSectionAndSwitchesRuntimeBanner() {
        ConversationSession session = new ConversationSession(tempDir);
        session.setPlanMode(true);

        String prompt = SystemPromptBuilder.builder().build()
                .build(ToolVisibility.empty(), tempDir, session);

        assertSectionsInOrder(prompt, "## Runtime Mode", "## Plan Mode");
        assertTrue(prompt.contains("Plan Mode"), "runtime banner must announce plan mode");
        assertTrue(prompt.contains("<proposed_plan>"),
                "plan mode rules must mention the proposed_plan delimiter");
        assertFalse(prompt.contains("## Long-Running Workflow"),
                "plan mode is incompatible with long-running");
    }

    @Test
    void reusesPromptStringWhenSessionFingerprintIsUnchanged() {
        ConversationSession session = new ConversationSession(tempDir);
        SystemPromptBuilder builder = SystemPromptBuilder.builder().build();

        String first = builder.build(ToolVisibility.empty(), session.workingDirectory(), session);
        String second = builder.build(ToolVisibility.empty(), session.workingDirectory(), session);

        assertSame(first, second);

        session.addMessage(madacode.core.model.Message.user("hello"));
        String afterChange = builder.build(ToolVisibility.empty(), session.workingDirectory(), session);

        assertNotSame(first, afterChange);
    }

    private static void assertSectionsInOrder(String prompt, String... sectionHeaders) {
        int previousIndex = -1;
        String previousHeader = null;
        for (String header : sectionHeaders) {
            int index = prompt.indexOf(header);
            assertTrue(index >= 0, "expected section header to be present: " + header);
            if (previousIndex >= 0) {
                assertTrue(index > previousIndex,
                        "expected '" + header + "' to appear after '" + previousHeader + "'");
            }
            previousIndex = index;
            previousHeader = header;
        }
        // Sanity: there should be exactly one of each header.
        for (String header : sectionHeaders) {
            int first = prompt.indexOf(header);
            int second = prompt.indexOf(header, first + 1);
            assertEquals(-1, second, "section header should appear exactly once: " + header);
        }
    }

    private static SkillLoader fixedLoader(Skill skill) {
        return () -> List.of(skill);
    }

    private static final class StubMemoryLoader extends MemoryLoader {
        private final String rendered;

        private StubMemoryLoader(String rendered) {
            super(new madacode.memory.AgentsMdLoader(), null, true);
            this.rendered = rendered;
        }

        @Override
        public Optional<String> renderForSystemPrompt(Path cwd) {
            return Optional.of(rendered);
        }
    }
}
