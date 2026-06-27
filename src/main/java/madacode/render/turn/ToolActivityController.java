package madacode.render.turn;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.render.tool.ToolDisplayRegistry;
import madacode.render.tool.ToolProgressLine;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class ToolActivityController {

    private final TurnView turnView;
    private final ToolDisplayRegistry displayRegistry;
    private final ToolGroupingPolicy groupingPolicy;
    private final Map<String, ToolCardRenderable> cardsByToolId = new LinkedHashMap<>();
    private final Map<String, ToolGroupRenderable> groupsByToolId = new LinkedHashMap<>();
    private ToolGroupRenderable activeExplorationGroup;

    ToolActivityController(TurnView turnView, ToolDisplayRegistry displayRegistry,
                           ToolGroupingPolicy groupingPolicy) {
        this.turnView = Objects.requireNonNull(turnView, "turnView");
        this.displayRegistry = Objects.requireNonNull(displayRegistry, "displayRegistry");
        this.groupingPolicy = Objects.requireNonNull(groupingPolicy, "groupingPolicy");
    }

    boolean contains(String toolUseId) {
        return cardsByToolId.containsKey(toolUseId) || groupsByToolId.containsKey(toolUseId);
    }

    void registerTool(String toolUseId, String toolName, ObjectNode input) {
        if (contains(toolUseId)) {
            return;
        }
        ToolActivityDescriptor descriptor = groupingPolicy.describe(toolName, input);
        if (descriptor.grouping() == ToolActivityGrouping.NEVER_GROUP) {
            activeExplorationGroup = null;
            return;
        }
        if (descriptor.groupableExploration()) {
            if (activeExplorationGroup == null || activeExplorationGroup.isFinalized()) {
                activeExplorationGroup = new ToolGroupRenderable(displayRegistry);
                turnView.add(activeExplorationGroup);
            }
            ToolInvocationModel invocation = new ToolInvocationModel(
                    toolUseId, toolName, input, descriptor);
            activeExplorationGroup.add(invocation);
            groupsByToolId.put(toolUseId, activeExplorationGroup);
            return;
        }
        activeExplorationGroup = null;
        ToolCardRenderable card = new ToolCardRenderable(toolUseId, toolName, input, displayRegistry);
        cardsByToolId.put(toolUseId, card);
        turnView.add(card);
    }

    boolean markStarted(String toolUseId) {
        ToolCardRenderable card = cardsByToolId.get(toolUseId);
        if (card != null) {
            card.markStarted();
            return true;
        }
        ToolGroupRenderable group = groupsByToolId.get(toolUseId);
        return group != null && group.markStarted(toolUseId);
    }

    boolean setResultOutput(String toolUseId, boolean success, String output) {
        ToolCardRenderable card = cardsByToolId.get(toolUseId);
        if (card != null) {
            card.setResultOutput(success, output);
            return true;
        }
        ToolGroupRenderable group = groupsByToolId.get(toolUseId);
        return group != null && group.setResultOutput(toolUseId, success, output);
    }

    boolean finalizeTool(String toolUseId, boolean success, long durationMs) {
        ToolCardRenderable card = cardsByToolId.get(toolUseId);
        if (card != null) {
            card.finalizeTool(success, durationMs);
            return true;
        }
        ToolGroupRenderable group = groupsByToolId.get(toolUseId);
        return group != null && group.finalizeTool(toolUseId, success, durationMs);
    }

    boolean appendProgress(String toolUseId, ToolProgressLine line) {
        ToolCardRenderable card = cardsByToolId.get(toolUseId);
        if (card != null) {
            card.appendProgress(line);
            return true;
        }
        ToolGroupRenderable group = groupsByToolId.get(toolUseId);
        return group != null && group.appendProgress(toolUseId, line);
    }

    boolean enterPermissionPhase(String toolUseId) {
        ToolCardRenderable card = cardsByToolId.get(toolUseId);
        if (card != null) {
            card.enterPermissionPhase();
            return true;
        }
        ToolGroupRenderable group = groupsByToolId.get(toolUseId);
        return group != null && group.enterPermissionPhase(toolUseId);
    }

    boolean resolvePermission(String toolUseId) {
        ToolCardRenderable card = cardsByToolId.get(toolUseId);
        if (card != null) {
            card.resolvePermission();
            return true;
        }
        ToolGroupRenderable group = groupsByToolId.get(toolUseId);
        return group != null && group.resolvePermission(toolUseId);
    }

    boolean markDenied(String toolUseId, String reason) {
        ToolCardRenderable card = cardsByToolId.get(toolUseId);
        if (card != null) {
            card.markDenied(reason);
            return true;
        }
        ToolGroupRenderable group = groupsByToolId.get(toolUseId);
        return group != null && group.markDenied(toolUseId, reason);
    }

    boolean setPermissionSelected(String toolUseId, int idx) {
        ToolCardRenderable card = cardsByToolId.get(toolUseId);
        if (card != null) {
            card.setPermissionSelected(idx);
            return true;
        }
        ToolGroupRenderable group = groupsByToolId.get(toolUseId);
        return group != null && group.setPermissionSelected(toolUseId, idx);
    }

    boolean hasPendingActivity() {
        for (ToolCardRenderable card : cardsByToolId.values()) {
            if (!card.isFinalized()) {
                return true;
            }
        }
        for (ToolGroupRenderable group : uniqueGroups()) {
            if (!group.isFinalized()) {
                return true;
            }
        }
        return false;
    }

    void finalizeUnfinishedAsFailed() {
        for (ToolCardRenderable card : cardsByToolId.values()) {
            if (!card.isFinalized()) {
                card.finalizeTool(false, 0);
            }
        }
        for (ToolGroupRenderable group : uniqueGroups()) {
            if (!group.isFinalized()) {
                group.finalizeUnfinishedAsFailed();
            }
        }
    }

    void removeUnfinalized() {
        cardsByToolId.entrySet().removeIf(entry -> {
            ToolCardRenderable card = entry.getValue();
            if (card.isFinalized()) {
                return false;
            }
            turnView.remove(card);
            return true;
        });
        for (ToolGroupRenderable group : uniqueGroups()) {
            if (!group.isFinalized()) {
                turnView.remove(group);
                groupsByToolId.entrySet().removeIf(entry -> entry.getValue() == group);
                if (activeExplorationGroup == group) {
                    activeExplorationGroup = null;
                }
            }
        }
    }

    void clear() {
        cardsByToolId.clear();
        groupsByToolId.clear();
        activeExplorationGroup = null;
    }

    private Set<ToolGroupRenderable> uniqueGroups() {
        return new LinkedHashSet<>(groupsByToolId.values());
    }
}
