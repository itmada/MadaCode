package madacode.longrunning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.core.model.ToolResult;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.tool.Tool;
import madacode.tool.ToolNames;
import madacode.tool.VisibleTools;
import madacode.tool.access.ToolAccessResolver;
import madacode.tool.access.ToolAccessScope;
import madacode.tool.access.ToolCapabilityProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the long-running worker capability set so it cannot drift silently. The worker
 * may use exactly {@link LongRunningCapabilityPolicy#WORKER_TOOLS} (ordinary file/shell
 * tools, update_plan, worker_report, longrun_task_update) and nothing else — not even
 * core tools like tool_search.
 */
class LongRunningCapabilityPolicyTest {

    private final ToolAccessResolver resolver = ToolAccessResolver.defaultResolver();

    @TempDir
    Path tempDir;

    @Test
    void workerSessionExposesExactlyTheWorkerCapabilitySet() {
        ToolAccessScope scope = workerScope(LongRunningStage.RUNNING);

        // Candidate tools: the full worker set plus a few that must stay out.
        List<Tool<?>> candidates = List.of(
                fake(ToolNames.FILE_READ), fake(ToolNames.GLOB), fake(ToolNames.GREP),
                fake(ToolNames.FILE_WRITE), fake(ToolNames.FILE_EDIT), fake(ToolNames.BASH),
                fake(ToolNames.UPDATE_PLAN), fake(ToolNames.WORKER_REPORT), fake(ToolNames.LONGRUN_TASK_UPDATE),
                fake(ToolNames.TOOL_SEARCH), fake(ToolNames.LONGRUN_PLAN_UPDATE), fake("web_fetch"));

        VisibleTools visible = resolver.visibleTools(candidates, scope);

        assertEquals(LongRunningCapabilityPolicy.WORKER_TOOLS, visible.names());
        assertTrue(visible.names().contains(ToolNames.UPDATE_PLAN), "update_plan is part of the worker set");
        // Core tools that are not in the worker set must not leak in.
        assertFalse(visible.names().contains(ToolNames.TOOL_SEARCH));
        assertFalse(visible.names().contains(ToolNames.LONGRUN_PLAN_UPDATE));
    }

    @Test
    void workerCannotLoadOrCallToolsOutsideTheCapabilitySet() {
        ToolAccessScope scope = workerScope(LongRunningStage.RUNNING);
        Tool<?> outside = fake("web_fetch");

        assertFalse(resolver.decideForToolSearch(outside, scope).loadable());
        assertNotNull(resolver.executionDenialReason(outside, scope));
        // A worker tool is callable.
        assertNull(resolver.executionDenialReason(fake(ToolNames.FILE_READ), scope));
    }

    @Test
    void workerCapabilityDenialTakesPrecedenceOverRequestExposureSnapshot() {
        ToolAccessScope scope = workerScope(LongRunningStage.RUNNING)
                .withRequestExposedToolNames(Set.of());
        Tool<?> outside = fake("web_fetch");

        String denial = resolver.exposedToolDenialReason(outside, scope);

        assertNotNull(denial);
        assertTrue(denial.contains("current agent capability set"));
        assertFalse(denial.contains("not exposed"));
    }

    @Test
    void workerThatHasNoReportButLeftRunningStageLosesAllTools() {
        for (LongRunningStage stage : List.of(
                LongRunningStage.DRAFT,
                LongRunningStage.INTERRUPT,
                LongRunningStage.COMPLETED,
                LongRunningStage.CANCELLED,
                LongRunningStage.FAILED)) {
            ToolAccessScope scope = workerScope(stage);
            VisibleTools visible = resolver.visibleTools(
                    List.of(fake(ToolNames.FILE_READ), fake(ToolNames.WORKER_REPORT)), scope);
            assertTrue(visible.isEmpty(), "worker tools should be hidden in " + stage);
        }
    }

    @Test
    void controlSessionExposesLifecycleToolsOnlyInDraftOrInterrupt() {
        Tool<?> planUpdate = fake(ToolNames.LONGRUN_PLAN_UPDATE);
        Tool<?> transitionRequest = fake(ToolNames.LONGRUN_STATE_TRANSITION_REQUEST);
        Tool<?> taskUpdate = fake(ToolNames.LONGRUN_TASK_UPDATE);

        assertNull(resolver.executionDenialReason(planUpdate, controlScope(LongRunningStage.DRAFT)));
        assertNull(resolver.executionDenialReason(planUpdate, controlScope(LongRunningStage.INTERRUPT)));
        assertNull(resolver.executionDenialReason(transitionRequest, controlScope(LongRunningStage.DRAFT)));
        assertNull(resolver.executionDenialReason(transitionRequest, controlScope(LongRunningStage.INTERRUPT)));
        for (LongRunningStage stage : List.of(
                LongRunningStage.RUNNING,
                LongRunningStage.COMPLETED,
                LongRunningStage.CANCELLED,
                LongRunningStage.FAILED)) {
            assertNotNull(resolver.executionDenialReason(planUpdate, controlScope(stage)),
                    "plan update should be denied in " + stage);
            assertNotNull(resolver.executionDenialReason(transitionRequest, controlScope(stage)),
                    "transition request should be denied in " + stage);
        }
        // Worker-only lifecycle tools are never callable from the control session.
        assertNotNull(resolver.executionDenialReason(taskUpdate, controlScope(LongRunningStage.DRAFT)));
    }

    @Test
    void commonSessionDeniesLifecycleToolsButKeepsCoreToolsVisible() {
        ConversationSession session = new ConversationSession(tempDir);
        ToolAccessScope scope = new ToolUseContext(tempDir, session).toolAccessScope();

        assertNotNull(resolver.executionDenialReason(fake(ToolNames.WORKER_REPORT), scope));
        VisibleTools visible = resolver.visibleTools(
                List.of(fake(ToolNames.FILE_READ), fake(ToolNames.WORKER_REPORT)), scope);
        assertEquals(Set.of(ToolNames.FILE_READ), visible.names());
    }

    @Test
    void planModeHidesUpdatePlanFromVisibleTools() {
        ConversationSession session = new ConversationSession(tempDir);
        session.setPlanMode(true);
        ToolAccessScope scope = new ToolUseContext(tempDir, session).toolAccessScope();

        VisibleTools visible = resolver.visibleTools(
                List.of(fake(ToolNames.FILE_READ), fake(ToolNames.BASH),
                        fake(ToolNames.UPDATE_PLAN), fake(ToolNames.FILE_EDIT)),
                scope);

        assertEquals(Set.of(ToolNames.FILE_READ, ToolNames.BASH), visible.names());
        assertNotNull(resolver.executionDenialReason(fake(ToolNames.UPDATE_PLAN), scope));
    }

    @Test
    void planModeHidesLongRunningLifecycleMutationTools() {
        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DRAFT);
        session.setPlanMode(true);
        ToolAccessScope scope = new ToolUseContext(tempDir, session).toolAccessScope();

        VisibleTools visible = resolver.visibleTools(
                List.of(fake(ToolNames.FILE_READ), fake(ToolNames.LONGRUN_PLAN_UPDATE),
                        fake(ToolNames.LONGRUN_STATE_TRANSITION_REQUEST)),
                scope);

        assertEquals(Set.of(ToolNames.FILE_READ), visible.names());
        assertNotNull(resolver.executionDenialReason(fake(ToolNames.LONGRUN_PLAN_UPDATE), scope));
        assertNotNull(resolver.executionDenialReason(fake(ToolNames.LONGRUN_STATE_TRANSITION_REQUEST), scope));
    }

    @Test
    void subAgentInsideRestrictedSessionCannotExceedWorkflowFloor() {
        ConversationSession workerSession = new ConversationSession(tempDir);
        workerSession.setWorkflowMode(SessionMode.LONG_RUNNING);
        workerSession.setLongRunningStage(LongRunningStage.RUNNING);
        workerSession.setLongRunningWorkerSession(true);

        // A sub-agent allow-list that would grant a tool outside the worker set.
        ToolCapabilityProfile childProfile = ToolCapabilityProfile.subAgentRestrictedAllowList(
                "child", Set.of(ToolNames.FILE_READ, "web_fetch"), Set.of());
        ToolAccessScope scope = ToolAccessScope.forSubAgent(workerSession, childProfile, Set.of());

        // web_fetch is allowed by the child profile but denied by the worker floor:
        // the effective capability is the intersection of the two, not the child alone.
        assertNotNull(resolver.executionDenialReason(fake("web_fetch"), scope));
        // file_read is allowed by both, so it stays callable.
        assertNull(resolver.executionDenialReason(fake(ToolNames.FILE_READ), scope));
    }

    private ToolAccessScope workerScope(LongRunningStage stage) {
        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(stage);
        session.setLongRunningWorkerSession(true);
        return new ToolUseContext(tempDir, session).toolAccessScope();
    }

    private ToolAccessScope controlScope(LongRunningStage stage) {
        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(stage);
        return new ToolUseContext(tempDir, session).toolAccessScope();
    }

    private static Tool<?> fake(String name) {
        return new Tool<Object>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return name;
            }

            @Override
            public Class<Object> inputType() {
                return Object.class;
            }

            @Override
            public boolean isReadOnly() {
                return !Set.of(
                                ToolNames.BASH,
                                ToolNames.FILE_EDIT,
                                ToolNames.FILE_WRITE,
                                ToolNames.UPDATE_PLAN,
                                ToolNames.LONGRUN_PLAN_UPDATE,
                                ToolNames.LONGRUN_TASK_UPDATE,
                                ToolNames.LONGRUN_STATE_TRANSITION_REQUEST)
                        .contains(name);
            }

            @Override
            public boolean isPlanModeSafe() {
                if (ToolNames.BASH.equals(name)) {
                    return true;
                }
                return isReadOnly();
            }

            @Override
            public ObjectNode inputSchema(ObjectMapper mapper) {
                ObjectNode schema = mapper.createObjectNode();
                schema.put("type", "object");
                return schema;
            }

            @Override
            public ToolResult execute(Object input, ToolUseContext context) {
                return new ToolResult(name, true, "");
            }
        };
    }
}
