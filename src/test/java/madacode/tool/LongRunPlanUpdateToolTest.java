package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.core.model.ToolResult;
import madacode.core.session.ConversationSession;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.longrunning.CreateTaskRequest;
import madacode.longrunning.FeatureItem;
import madacode.longrunning.LongRunningTaskStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LongRunPlanUpdateToolTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void replaceFeatureListPreservesExistingPassesWhenOmitted() {
        LongRunningTaskStore store = new LongRunningTaskStore(tempDir);
        store.createTask(new CreateTaskRequest("task-plan", "Plan", "DRAFT", null, "session-ctrl", null));
        store.writeInitialFeatureList("task-plan", List.of(
                new FeatureItem("feature-a", "core", "high", "Old text", List.of(), List.of("verify"), false)));
        store.markFeaturePassed("task-plan", "feature-a");

        ConversationSession session = controlSession();
        ToolUseContext context = new ToolUseContext(tempDir, session);

        ObjectNode input = mapper.createObjectNode();
        input.put("action", "replace_feature_list");
        ArrayNode features = mapper.createArrayNode();
        ObjectNode feature = mapper.createObjectNode();
        feature.put("id", "feature-a");
        feature.put("category", "core");
        feature.put("priority", "high");
        feature.put("description", "Updated text");
        feature.set("depends_on", mapper.createArrayNode());
        feature.set("verification_steps", mapper.createArrayNode().add("verify again"));
        features.add(feature);
        input.set("features", features);

        ToolResult result = ToolTestSupport.invoke(new LongRunPlanUpdateTool(), input, context);

        assertTrue(result.success(), result.output());
        assertTrue(store.readFeatureList("task-plan").getFirst().passes());
    }

    private ConversationSession controlSession() {
        ConversationSession session = new ConversationSession(tempDir);
        session.setWorkflowMode(SessionMode.LONG_RUNNING);
        session.setLongRunningStage(LongRunningStage.DRAFT);
        session.setLongRunningTaskId("task-plan");
        return session;
    }
}
