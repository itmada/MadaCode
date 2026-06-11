package madacode.services.api;

import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.model.MessageKind;
import madacode.core.model.MessageRole;

import java.util.ArrayList;
import java.util.List;

public final class ApiMessageProjection {

    public List<Message> project(List<Message> sessionMessages) {
        List<Message> projected = new ArrayList<>();
        for (Message message : sessionMessages) {
            if (message.role() == MessageRole.SYSTEM) {
                continue;
            }
            if (projected.isEmpty()) {
                projected.add(message);
                continue;
            }
            Message previous = projected.getLast();
            if (previous.role() != message.role()) {
                projected.add(message);
                continue;
            }
            List<ContentBlock> mergedBlocks = new ArrayList<>(
                    previous.contentBlocks().size() + message.contentBlocks().size());
            mergedBlocks.addAll(previous.contentBlocks());
            mergedBlocks.addAll(message.contentBlocks());
            if (previous.role() == MessageRole.USER) {
                mergedBlocks = stablePartitionToolResults(mergedBlocks);
            }
            projected.set(projected.size() - 1, Message.of(
                    previous.role(),
                    mergedBlocks,
                    mergedKind(previous, message)));
        }
        return List.copyOf(projected);
    }

    private MessageKind mergedKind(Message previous, Message next) {
        return previous.isControllerEvent() && next.isControllerEvent()
                ? MessageKind.CONTROLLER_EVENT
                : MessageKind.STANDARD;
    }

    private static List<ContentBlock> stablePartitionToolResults(List<ContentBlock> blocks) {
        List<ContentBlock> toolResults = new ArrayList<>();
        List<ContentBlock> others = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block instanceof ContentBlock.ToolResultBlock) {
                toolResults.add(block);
            } else {
                others.add(block);
            }
        }
        if (toolResults.isEmpty()) {
            return blocks;
        }
        List<ContentBlock> merged = new ArrayList<>(toolResults.size() + others.size());
        merged.addAll(toolResults);
        merged.addAll(others);
        return merged;
    }
}
