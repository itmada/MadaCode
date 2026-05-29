package madacode.eval;

import madacode.core.ToolCall;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

public record MockApiResponse(
        String assistantText,
        List<ToolCallStub> toolCalls) {

    public record ToolCallStub(String id, String name, ObjectNode input) {
        public ToolCall toToolCall() {
            return new ToolCall(id, name, input);
        }
    }
}
