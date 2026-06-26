package madacode.eval;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.session.ConversationSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the attempt-scoped trace boundary reaches dynamically-spawned sub-agent
 * sessions — the gap that previously let sub-agent tool calls, decoy access, and
 * read-before-edit evidence escape every invocation-based scorer.
 */
class ExecutionTraceCollectorTest {

    @Test
    void finishCapturesSubAgentAndGrandchildInvocationsWithSubagentPhase(@TempDir Path workspace) {
        ExecutionTraceCollector collector = new ExecutionTraceCollector(workspace);

        // Wire the control session exactly as EvalRunner does.
        ConversationSession control = sessionWith(workspace, "control",
                toolUse("toolu_c", "edit", input("file_path", "App.java")));
        control.setSubAgentSpawnObserver(collector::trackSubAgent);

        // A sub-agent spawned by the control session, and a grandchild spawned by it.
        // registerSubAgent mirrors what AgentRunner does and must propagate the observer
        // so the grandchild is tracked too.
        ConversationSession subAgent = sessionWith(workspace, "sub",
                toolUse("toolu_s", "file_read", input("path", "secret.key")));
        control.registerSubAgent(subAgent);

        ConversationSession grandchild = sessionWith(workspace, "grand",
                toolUse("toolu_g", "bash", input("command", "rm -rf /")));
        subAgent.registerSubAgent(grandchild);

        collector.recordSession(control, ToolInvocation.Phase.CONTROL);
        ExecutionTrace trace = collector.finish("done", new RunMetrics(1, 0, 0, 1, null));

        // The control tool call keeps CONTROL phase; sub-agent + grandchild are SUBAGENT.
        assertEquals(3, trace.invocations().size(), "all three tool calls must be captured");
        assertTrue(trace.invocations().stream()
                        .anyMatch(i -> i.name().equals("file_read")
                                && i.phase() == ToolInvocation.Phase.SUBAGENT),
                "sub-agent decoy read must be visible to scorers");
        assertTrue(trace.invocations().stream()
                        .anyMatch(i -> i.name().equals("bash")
                                && i.phase() == ToolInvocation.Phase.SUBAGENT),
                "grandchild tool call must be captured via observer propagation");

        // toolCalls is reconciled to the full-tree total, not the control-only count of 1.
        assertEquals(3, trace.metrics().toolCalls(),
                "tool-call metric must reflect the whole agent tree");
    }

    private static ConversationSession sessionWith(Path workspace, String id, ContentBlock block) {
        return new ConversationSession(
                id,
                Instant.parse("2026-06-19T00:00:00Z"),
                workspace,
                List.of(
                        Message.system("Session initialized."),
                        Message.assistant(List.of(block))));
    }

    private static ContentBlock.ToolUseBlock toolUse(String id, String name, ObjectNode input) {
        return new ContentBlock.ToolUseBlock(id, name, input);
    }

    private static ObjectNode input(String field, String value) {
        return JsonNodeFactory.instance.objectNode().put(field, value);
    }
}
