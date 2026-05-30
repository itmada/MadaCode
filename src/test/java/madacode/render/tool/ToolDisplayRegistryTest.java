package madacode.render.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolDisplayRegistryTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolDisplayRegistry registry = ToolDisplayRegistry.defaults();

    @Test
    void summarizesMavenBashOutput() {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "./mvnw test");

        ToolDisplay display = registry.renderResult("bash", input, true, """
                [INFO] Results:
                [INFO]
                [INFO] Tests run: 334, Failures: 0, Errors: 0, Skipped: 0
                [INFO] BUILD SUCCESS
                """, 3815);

        assertEquals(DisplayStatus.SUCCESS, display.status());
        assertEquals("Bash(./mvnw test)", display.title());
        assertEquals("BUILD SUCCESS · 334 tests · 3.8s", display.summary());
    }

    @Test
    void summarizesReadOutputByLineCount() {
        ObjectNode input = mapper.createObjectNode();
        input.put("path", "src/main/java/App.java");

        ToolDisplay display = registry.renderResult("file_read", input, true, "a\nb\nc\n", 10);

        assertEquals("Read(src/main/java/App.java)", display.title());
        assertEquals("Read 3 lines", display.summary());
    }

    @Test
    void summarizesSearchAndGlobCounts() {
        ObjectNode grepInput = mapper.createObjectNode();
        grepInput.put("pattern", "QueryEngine");
        ToolDisplay grep = registry.renderResult("grep", grepInput, true, "A.java\nB.java\n", 10);
        assertEquals("Found 2 files", grep.summary());

        ObjectNode globInput = mapper.createObjectNode();
        globInput.put("pattern", "src/**/*.java");
        ToolDisplay glob = registry.renderResult("glob", globInput, true, "A.java\nB.java\nC.java\n", 10);
        assertEquals("Found 3 files", glob.summary());
    }

    @Test
    void editDisplayShowsLineChangeCountsOnly() {
        ObjectNode input = mapper.createObjectNode();
        input.put("file_path", "/tmp/App.java");

        ToolDisplay display = registry.renderResult("edit", input, true, """
                The file /tmp/App.java has been updated successfully.
                Line changes: +1 -1
                """, 42);

        String plainSummary = display.summary().replaceAll("\\[[0-9;]*[a-zA-Z]", "");
        assertEquals("Updated 1 file  +1 -1", plainSummary);
        assertTrue(display.summary().contains(""), "line counts should be ANSI-styled");
        assertTrue(display.detailLines().isEmpty());
    }

    @Test
    void agentDisplayUsesSubagentTypeAndDescription() {
        ObjectNode input = mapper.createObjectNode();
        input.put("subagent_type", "explorer");
        input.put("description", "find README location");
        input.put("prompt", "find README");

        ToolDisplay running = registry.renderStart("agent", input);
        assertEquals("Agent(explorer)", running.title());
        assertEquals("find README location", running.summary());

        ToolDisplay runningWithActivity = registry.renderRunning(
                "agent",
                input,
                List.of(
                        ToolProgressLine.activity("▸ Reading README.md"),
                        ToolProgressLine.activity("▸ Searching for \"README\""),
                        ToolProgressLine.output("should not be summarized")));
        assertEquals("Agent(explorer)", runningWithActivity.title());
        assertEquals("find README location", runningWithActivity.summary());
        assertEquals("2 tool uses", runningWithActivity.detailLines().getFirst());
        assertTrue(runningWithActivity.detailLines().stream()
                .anyMatch(line -> line.contains("▸ Reading README.md")));
        assertTrue(runningWithActivity.detailLines().stream()
                .noneMatch(line -> line.contains("should not be summarized")));

        ToolDisplay success = registry.renderResult(
                "agent",
                input,
                true,
                "README.md\ndocs/README.md",
                15636);
        assertEquals(DisplayStatus.SUCCESS, success.status());
        assertTrue(success.detailLines().isEmpty(),
                "successful sub-agent output is answered by the parent and should not be duplicated in the tool card");

        ToolDisplay failed = registry.renderResult(
                "agent",
                input,
                false,
                "Sub-agent did not complete (MAX_ITERATIONS): still exploring",
                15636);
        assertEquals(DisplayStatus.FAILED, failed.status());
        assertEquals("Agent(explorer)", failed.title());
        assertEquals("Failed · 15.6s", failed.summary());
        assertTrue(failed.detailLines().isEmpty(),
                "sub-agent failure details belong in tool_result for the parent agent, not in the lifecycle card");

        ToolDisplay denied = registry.renderDenied("agent", input, "User denied permission", 0);
        assertEquals(DisplayStatus.DENIED, denied.status());
        assertEquals("Permission denied", denied.summary());
        assertTrue(denied.detailLines().stream().anyMatch(line -> line.contains("User denied permission")));
    }

    @Test
    void skillDisplayUsesLifecycleOnlyCard() {
        ObjectNode input = mapper.createObjectNode();
        input.put("skill", "code-review");
        input.put("task", "review current changes");

        ToolDisplay running = registry.renderStart("skill", input);
        assertEquals("Skill(code-review)", running.title());
        assertEquals("review current changes", running.summary());

        ToolDisplay success = registry.renderResult("skill", input, true, """
                rendered prompt body
                more body
                """, 88);
        assertEquals(DisplayStatus.SUCCESS, success.status());
        assertTrue(success.detailLines().isEmpty(),
                "successful skill output is consumed by the parent agent and should not be duplicated in the card");

        ToolDisplay failed = registry.renderResult("skill", input, false, """
                line 1
                line 2
                line 3
                line 4
                """, 88);
        assertEquals(DisplayStatus.FAILED, failed.status());
        assertEquals("Skill(code-review)", failed.title());
        assertEquals("Failed · 88ms", failed.summary());
        assertTrue(failed.detailLines().isEmpty(),
                "skill failure details belong in tool_result for the parent agent, not in the lifecycle card");
    }

    @Test
    void skillDisplaySupportsLegacyNameAlias() {
        ObjectNode input = mapper.createObjectNode();
        input.put("skill", "code-review");
        input.put("task", "review current changes");

        ToolDisplay running = registry.renderStart("Skill", input);
        assertEquals("Skill(code-review)", running.title());
    }

    @Test
    void bashRunningShowsOutputTailWithHiddenCount() {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", "ls -R /");

        ToolDisplay empty = registry.renderRunning("bash", input, List.of());
        assertEquals("Bash(ls -R /)", empty.title());
        assertEquals("Running...", empty.summary());
        assertTrue(empty.detailLines().isEmpty(),
                "no output → no detail lines, defer to ToolCardRenderable default tail");

        List<ToolProgressLine> few = List.of(
                ToolProgressLine.output("line 1"),
                ToolProgressLine.output("line 2"),
                ToolProgressLine.output("line 3"));
        ToolDisplay small = registry.renderRunning("bash", input, few);
        assertEquals(3, small.detailLines().size());
        assertTrue(small.detailLines().stream().noneMatch(line -> line.contains("hidden")),
                "≤10 lines shows everything, no hidden marker");

        List<ToolProgressLine> many = new java.util.ArrayList<>();
        for (int i = 1; i <= 14; i++) {
            many.add(ToolProgressLine.output("line " + i));
        }
        ToolDisplay overflow = registry.renderRunning("bash", input, many);
        assertEquals(11, overflow.detailLines().size(), "1 hidden marker + 10 tail lines");
        assertTrue(overflow.detailLines().getFirst().contains("4 earlier lines hidden"),
                "first detail line is hidden marker, actual: " + overflow.detailLines().getFirst());
        assertTrue(overflow.detailLines().get(1).contains("line 5"),
                "tail starts at line 5 (14 - 10 + 1)");
        assertTrue(overflow.detailLines().getLast().contains("line 14"));

        ToolProgressSnapshot withDropped = new ToolProgressSnapshot(
                List.of(
                        ToolProgressLine.output("tail-a"),
                        ToolProgressLine.output("tail-b")),
                17,
                0);
        ToolDisplay dropped = registry.renderRunning("bash", input, withDropped);
        assertTrue(dropped.detailLines().getFirst().contains("17 earlier lines hidden"),
                "dropped lines counted into hidden marker: " + dropped.detailLines().getFirst());

        ToolProgressLine activityNoise = ToolProgressLine.activity("▸ should be ignored");
        ToolDisplay filtered = registry.renderRunning("bash", input,
                List.of(activityNoise, ToolProgressLine.output("real output")));
        assertEquals(1, filtered.detailLines().size(),
                "ACTIVITY lines must not appear in Bash output tail");
        assertTrue(filtered.detailLines().getFirst().contains("real output"));
    }

    @Test
    void grepRunningShowsLatestMetric() {
        ObjectNode input = mapper.createObjectNode();
        input.put("pattern", "QueryEngine");
        input.put("path", "src/main");

        ToolDisplay empty = registry.renderRunning("grep", input, List.of());
        assertEquals("Searching...", empty.summary(),
                "no METRIC yet → fall back to renderStart-style placeholder");

        ToolDisplay withMetrics = registry.renderRunning("grep", input, List.of(
                ToolProgressLine.metric("scanned 200 files · 1 matches"),
                ToolProgressLine.metric("scanned 400 files · 3 matches"),
                ToolProgressLine.output("noise that must not become summary"),
                ToolProgressLine.metric("scanned 600 files · 5 matches")));
        assertEquals("scanned 600 files · 5 matches", withMetrics.summary(),
                "latest METRIC wins; OUTPUT is ignored");
        assertTrue(withMetrics.detailLines().isEmpty(),
                "grep RUNNING uses summary line only, no detail lines");
    }

    @Test
    void webFetchRunningShowsLatestMetric() {
        ObjectNode input = mapper.createObjectNode();
        input.put("url", "https://example.com");

        ToolDisplay empty = registry.renderRunning("web_fetch", input, List.of());
        assertEquals("Fetching...", empty.summary());

        ToolDisplay phaseFetching = registry.renderRunning("web_fetch", input, List.of(
                ToolProgressLine.metric("Fetching https://example.com")));
        assertEquals("Fetching https://example.com", phaseFetching.summary());

        ToolDisplay phaseReceived = registry.renderRunning("web_fetch", input, List.of(
                ToolProgressLine.metric("Fetching https://example.com"),
                ToolProgressLine.metric("Received 12 KB · text/html")));
        assertEquals("Received 12 KB · text/html", phaseReceived.summary(),
                "latest phase replaces earlier phase");

        ToolDisplay phaseExtracting = registry.renderRunning("web_fetch", input, List.of(
                ToolProgressLine.metric("Fetching https://example.com"),
                ToolProgressLine.metric("Received 12 KB · text/html"),
                ToolProgressLine.activity("ignored activity"),
                ToolProgressLine.metric("Extracting content")));
        assertEquals("Extracting content", phaseExtracting.summary(),
                "ACTIVITY ignored, latest METRIC wins");
    }

    @Test
    void activityDescriptionCoversMajorTools() {
        ObjectNode bash = mapper.createObjectNode();
        bash.put("command", "./mvnw -q test");
        assertEquals("Running ./mvnw -q test", registry.activityDescription("bash", bash));

        ObjectNode read = mapper.createObjectNode();
        read.put("path", "src/main/App.java");
        assertEquals("Reading src/main/App.java", registry.activityDescription("file_read", read));

        ObjectNode write = mapper.createObjectNode();
        write.put("file_path", "src/main/App.java");
        assertEquals("Writing src/main/App.java", registry.activityDescription("write", write));

        ObjectNode edit = mapper.createObjectNode();
        edit.put("file_path", "src/main/App.java");
        assertEquals("Editing src/main/App.java", registry.activityDescription("edit", edit));

        ObjectNode grep = mapper.createObjectNode();
        grep.put("pattern", "QueryEngine");
        grep.put("path", "src/main");
        assertEquals("Searching for \"QueryEngine\" in src/main", registry.activityDescription("grep", grep));

        ObjectNode glob = mapper.createObjectNode();
        glob.put("pattern", "src/**/*.java");
        assertEquals("Finding \"src/**/*.java\"", registry.activityDescription("glob", glob));

        ObjectNode web = mapper.createObjectNode();
        web.put("url", "https://example.com");
        assertEquals("Fetching https://example.com", registry.activityDescription("web_fetch", web));

        ObjectNode agent = mapper.createObjectNode();
        agent.put("subagent_type", "explorer");
        agent.put("description", "find README");
        assertEquals("Agent(explorer): find README", registry.activityDescription("agent", agent));

        ObjectNode skill = mapper.createObjectNode();
        skill.put("skill", "code-review");
        skill.put("task", "review current changes");
        assertEquals("Skill(code-review): review current changes", registry.activityDescription("skill", skill));
    }
}
