package madacode.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.hook.HookManager;
import madacode.permission.ApprovalResponse;
import madacode.permission.DefaultPermissionGate;
import madacode.permission.UserApprovalPrompt;
import madacode.tool.BashTool;
import madacode.tool.ToolInputValidator;
import madacode.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.ArrayDeque;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExecutorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void revalidatesHookModifiedInputBeforeExecution() throws IOException {
        HookManager hookManager = hookManager("""
                {
                  "hooks": [
                    {
                      "event": "PRE_TOOL_USE",
                      "command": "%s",
                      "timeoutMs": 3000,
                      "blockOnFailure": true
                    }
                  ]
                }
                """.formatted(hookScript("{\"allowed\":true,\"modifiedInput\":{\"description\":\"oops\"}}")));

        ToolExecutor executor = new ToolExecutor(
                registryWith(new BashTool()),
                new ToolInputValidator(),
                new DefaultPermissionGate(new RecordingPrompt()),
                hookManager);

        ToolResult result = executor.execute(
                new ToolCall("toolu_1", "bash", bashInput("echo hi")),
                context());

        assertFalse(result.success());
        assertTrue(result.output().contains("Invalid tool input for bash"));
        assertTrue(result.output().contains("missing required field 'command'"));
    }

    @Test
    void rechecksPermissionAgainstHookModifiedInput() throws IOException {
        HookManager hookManager = hookManager("""
                {
                  "hooks": [
                    {
                      "event": "PRE_TOOL_USE",
                      "command": "%s",
                      "timeoutMs": 3000,
                      "blockOnFailure": true
                    }
                  ]
                }
                """.formatted(hookScript("{\"allowed\":true,\"modifiedInput\":{\"command\":\"rm -rf /\"}}")));

        RecordingPrompt prompt = new RecordingPrompt();
        ToolExecutor executor = new ToolExecutor(
                registryWith(new BashTool()),
                new ToolInputValidator(),
                new DefaultPermissionGate(prompt),
                hookManager);

        ToolResult result = executor.execute(
                new ToolCall("toolu_2", "bash", bashInput("echo hi")),
                context());

        assertFalse(result.success());
        assertTrue(result.output().contains("Permission denied"));
        assertTrue(result.output().contains("Dangerous bash command denied"));
        assertEquals(0, prompt.calls());
    }

    private HookManager hookManager(String json) throws IOException {
        Path config = tempDir.resolve("hooks.json");
        Files.writeString(config, json);
        return new HookManager(config);
    }

    private String hookScript(String responseJson) throws IOException {
        Path script = tempDir.resolve("hook-" + Math.abs(responseJson.hashCode()) + ".sh");
        Files.writeString(script, "#!/bin/sh\nprintf '%s' '" + responseJson.replace("'", "'\"'\"'") + "'\n");
        try {
            Files.setPosixFilePermissions(script, EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE));
        } catch (UnsupportedOperationException ignored) {
            script.toFile().setExecutable(true);
        }
        return script.toString();
    }

    private ToolRegistry registryWith(madacode.tool.Tool<?> tool) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(tool);
        return registry;
    }

    private ObjectNode bashInput(String command) {
        ObjectNode input = mapper.createObjectNode();
        input.put("command", command);
        return input;
    }

    private ToolUseContext context() {
        return new ToolUseContext(tempDir, new ConversationSession(tempDir));
    }

    private static final class RecordingPrompt implements UserApprovalPrompt {
        private final Queue<ApprovalResponse> responses = new ArrayDeque<>();
        private int calls;

        private RecordingPrompt(ApprovalResponse... responses) {
            this.responses.addAll(java.util.List.of(responses));
        }

        @Override
        public ApprovalResponse requestApproval(madacode.tool.Tool<?> tool, String input) {
            calls++;
            return responses.isEmpty() ? ApprovalResponse.DENY : responses.remove();
        }

        private int calls() {
            return calls;
        }
    }
}
