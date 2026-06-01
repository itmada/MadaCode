package madacode.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import madacode.core.session.ConversationSession;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;
import madacode.plan.PlanItem;
import madacode.plan.PlanStatus;
import madacode.plan.TodoItem;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

class PlanToolsTest {

    private ConversationSession session;
    private ToolUseContext context;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        session = new ConversationSession();
        context = new ToolUseContext(java.nio.file.Path.of(System.getProperty("user.dir")), session);
        mapper = new ObjectMapper();
    }

    // ---- TaskCreate ----

    @Test
    void taskCreateSingle() {
        PlanCreateTool tool = new PlanCreateTool();
        ObjectNode input = mapper.createObjectNode();
        ArrayNode tasks = mapper.createArrayNode();
        ObjectNode task = mapper.createObjectNode();
        task.put("title", "Do something");
        task.put("description", "Need to do it");
        tasks.add(task);
        input.set("tasks", tasks);

        ToolResult result = ToolTestSupport.invoke(tool, input, context);
        assertTrue(result.success());
        assertTrue(result.output().contains("Do something"));
        assertEquals(1, session.plan().items().size());
        assertEquals(PlanStatus.PENDING, session.plan().items().getFirst().status());
    }

    @Test
    void planCreateWithDependencies() {
        PlanCreateTool tool = new PlanCreateTool();
        // Create first task
        ObjectNode input1 = mapper.createObjectNode();
        ArrayNode tasks1 = mapper.createArrayNode();
        ObjectNode t1 = mapper.createObjectNode();
        t1.put("title", "First");
        tasks1.add(t1);
        input1.set("tasks", tasks1);
        ToolTestSupport.invoke(tool, input1, context);

        // Create second task depending on first
        ObjectNode input2 = mapper.createObjectNode();
        ArrayNode tasks2 = mapper.createArrayNode();
        ObjectNode t2 = mapper.createObjectNode();
        t2.put("title", "Second");
        ArrayNode deps = mapper.createArrayNode();
        deps.add("1");
        t2.set("blockedBy", deps);
        tasks2.add(t2);
        input2.set("tasks", tasks2);

        ToolResult result = ToolTestSupport.invoke(tool, input2, context);
        assertTrue(result.success());
        assertEquals(2, session.plan().items().size());
        assertTrue(result.output().contains("blocked by: 1"));
    }

    @Test
    void planCreateRejectsEmptyTasks() {
        PlanCreateTool tool = new PlanCreateTool();
        ObjectNode input = mapper.createObjectNode();
        input.set("tasks", mapper.createArrayNode());

        ToolResult result = ToolTestSupport.invoke(tool, input, context);
        assertFalse(result.success());
    }

    @Test
    void planCreateRejectsBlankTitle() {
        PlanCreateTool tool = new PlanCreateTool();
        ObjectNode input = mapper.createObjectNode();
        ArrayNode tasks = mapper.createArrayNode();
        ObjectNode task = mapper.createObjectNode();
        task.put("title", "");
        tasks.add(task);
        input.set("tasks", tasks);

        ToolResult result = ToolTestSupport.invoke(tool, input, context);
        assertFalse(result.success());
    }

    @Test
    void planCreateRejectsCyclicDependency() {
        PlanCreateTool tool = new PlanCreateTool();
        ObjectNode input1 = mapper.createObjectNode();
        ArrayNode tasks1 = mapper.createArrayNode();
        ObjectNode t1 = mapper.createObjectNode();
        t1.put("title", "A");
        ArrayNode deps1 = mapper.createArrayNode();
        deps1.add("2");
        t1.set("blockedBy", deps1);
        tasks1.add(t1);
        input1.set("tasks", tasks1);
        ToolTestSupport.invoke(tool, input1, context);

        ObjectNode input2 = mapper.createObjectNode();
        ArrayNode tasks2 = mapper.createArrayNode();
        ObjectNode t2 = mapper.createObjectNode();
        t2.put("title", "B");
        ArrayNode deps2 = mapper.createArrayNode();
        deps2.add("1");
        t2.set("blockedBy", deps2);
        tasks2.add(t2);
        input2.set("tasks", tasks2);

        ToolResult result = ToolTestSupport.invoke(tool, input2, context);
        assertFalse(result.success());
        assertTrue(result.output().contains("Cyclic"));
    }

    @Test
    void planCreateRejectsMissingDependencyReference() {
        PlanCreateTool tool = new PlanCreateTool();
        ObjectNode input = mapper.createObjectNode();
        ArrayNode tasks = mapper.createArrayNode();
        ObjectNode task = mapper.createObjectNode();
        task.put("title", "Blocked task");
        ArrayNode deps = mapper.createArrayNode();
        deps.add("999");
        task.set("blockedBy", deps);
        tasks.add(task);
        input.set("tasks", tasks);

        ToolResult result = ToolTestSupport.invoke(tool, input, context);

        assertFalse(result.success());
        assertTrue(result.output().contains("Unknown dependency"));
    }

    @Test
    void planCreateAllowsDependenciesWithinSameBatch() {
        PlanCreateTool tool = new PlanCreateTool();
        ObjectNode input = mapper.createObjectNode();
        ArrayNode tasks = mapper.createArrayNode();
        ObjectNode first = mapper.createObjectNode();
        first.put("title", "First");
        tasks.add(first);
        ObjectNode second = mapper.createObjectNode();
        second.put("title", "Second");
        ArrayNode deps = mapper.createArrayNode();
        deps.add("1");
        second.set("blockedBy", deps);
        tasks.add(second);
        input.set("tasks", tasks);

        ToolResult result = ToolTestSupport.invoke(tool, input, context);

        assertTrue(result.success(), result.output());
        assertEquals(List.of("1"), session.plan().items().get(1).blockedBy());
    }

    // ---- TaskGet ----

    @Test
    void taskGetExisting() {
        PlanCreateTool create = new PlanCreateTool();
        ObjectNode input = mapper.createObjectNode();
        ArrayNode tasks = mapper.createArrayNode();
        ObjectNode task = mapper.createObjectNode();
        task.put("title", "Findable");
        tasks.add(task);
        input.set("tasks", tasks);
        ToolTestSupport.invoke(create, input, context);

        PlanGetTool get = new PlanGetTool();
        ObjectNode getInput = mapper.createObjectNode();
        getInput.put("id", "1");

        ToolResult result = ToolTestSupport.invoke(get, getInput, context);
        assertTrue(result.success());
        assertTrue(result.output().startsWith("id: 1\n"));
        assertTrue(result.output().contains("id: 1"));
        assertTrue(result.output().contains("Findable"));
    }

    @Test
    void taskGetMissing() {
        PlanGetTool get = new PlanGetTool();
        ObjectNode getInput = mapper.createObjectNode();
        getInput.put("id", "999");

        ToolResult result = ToolTestSupport.invoke(get, getInput, context);
        assertFalse(result.success());
        assertTrue(result.output().contains("not found"));
    }

    @Test
    void taskGetEmptyId() {
        PlanGetTool get = new PlanGetTool();
        ObjectNode getInput = mapper.createObjectNode();
        getInput.put("id", "");

        ToolResult result = ToolTestSupport.invoke(get, getInput, context);
        assertFalse(result.success());
    }

    // ---- TaskList ----

    @Test
    void taskListEmpty() {
        PlanListTool list = new PlanListTool();
        ObjectNode input = mapper.createObjectNode();

        ToolResult result = ToolTestSupport.invoke(list, input, context);
        assertTrue(result.success());
        assertTrue(result.output().contains("no plan items"));
    }

    @Test
    void taskListWithTasks() {
        session.plan().add(PlanItem.create("1", "First task", "", List.of()));
        session.plan().add(PlanItem.create("2", "Second task", "", List.of()));

        PlanListTool list = new PlanListTool();
        ObjectNode input = mapper.createObjectNode();

        ToolResult result = ToolTestSupport.invoke(list, input, context);
        assertTrue(result.success());
        assertTrue(result.output().contains("╭ 01 ○ [PENDING] 1  First task"));
        assertTrue(result.output().contains("╰ 02 ○ [PENDING] 2  Second task"));
    }

    @Test
    void taskListFilterByStatus() {
        session.plan().add(PlanItem.create("1", "Pending task", "", List.of()));
        var completed = PlanItem.create("2", "Done task", "", List.of())
                .transitionTo(PlanStatus.IN_PROGRESS).transitionTo(PlanStatus.COMPLETED);
        session.plan().add(completed);

        PlanListTool list = new PlanListTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("status", "completed");

        ToolResult result = ToolTestSupport.invoke(list, input, context);
        assertTrue(result.success());
        assertTrue(result.output().contains("╭ 01 ✓ [COMPLETED] 2  Done task"));
        assertFalse(result.output().contains("Pending task"));
    }

    @Test
    void taskListShowsBlockedDependencies() {
        session.plan().add(PlanItem.create("1", "Dependency", "", List.of()));
        session.plan().add(PlanItem.create("2", "Blocked task", "", List.of("1")));

        PlanListTool list = new PlanListTool();
        ToolResult result = ToolTestSupport.invoke(list, mapper.createObjectNode(), context);

        assertTrue(result.success());
        assertTrue(result.output().contains("╰ 02 ○ [PENDING] 2  Blocked task (blocked)"));
        assertTrue(result.output().contains("blocked by: 1"));
        assertTrue(result.output().contains("still blocked by: 1"));
    }

    @Test
    void taskListUsesDisplayOrderNotRealIdForNumbering() {
        session.plan().add(PlanItem.create("abc", "Non numeric", "", List.of()));

        PlanListTool list = new PlanListTool();
        ToolResult result = ToolTestSupport.invoke(list, mapper.createObjectNode(), context);

        assertTrue(result.success());
        assertTrue(result.output().contains("╭ 01 ○ [PENDING] abc  Non numeric"));
    }

    @Test
    void taskListSingleItemUsesExplicitSingleRowRail() {
        session.plan().add(PlanItem.create("1", "Solo", "", List.of()));

        PlanListTool list = new PlanListTool();
        ToolResult result = ToolTestSupport.invoke(list, mapper.createObjectNode(), context);

        assertTrue(result.success());
        assertTrue(result.output().startsWith("╭ 01 ○ [PENDING] 1  Solo"));
    }

    // ---- TaskUpdate ----

    @Test
    void taskUpdateStatus() {
        session.plan().add(PlanItem.create("1", "Task", "", List.of()));

        PlanUpdateTool update = new PlanUpdateTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("id", "1");
        input.put("status", "in_progress");

        ToolResult result = ToolTestSupport.invoke(update, input, context);
        assertTrue(result.success());
        assertEquals(PlanStatus.IN_PROGRESS, session.plan().find("1").orElseThrow().status());
    }

    @Test
    void taskUpdateBlockedByDependency() {
        session.plan().add(PlanItem.create("1", "Dep", "", List.of()));
        session.plan().add(PlanItem.create("2", "Main", "", List.of("1")));

        PlanUpdateTool update = new PlanUpdateTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("id", "2");
        input.put("status", "in_progress");

        ToolResult result = ToolTestSupport.invoke(update, input, context);
        assertFalse(result.success());
        assertTrue(result.output().contains("blocked"));
    }

    @Test
    void taskUpdateToCompleted() {
        session.plan().add(PlanItem.create("1", "Task", "", List.of())
                .transitionTo(PlanStatus.IN_PROGRESS));

        PlanUpdateTool update = new PlanUpdateTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("id", "1");
        input.put("status", "completed");

        ToolResult result = ToolTestSupport.invoke(update, input, context);
        assertTrue(result.success(), result.output());
        assertEquals(PlanStatus.COMPLETED, session.plan().find("1").orElseThrow().status());
    }

    @Test
    void taskUpdateInvalidStatus() {
        session.plan().add(PlanItem.create("1", "Task", "", List.of()));

        PlanUpdateTool update = new PlanUpdateTool();
        ObjectNode input = mapper.createObjectNode();
        input.put("id", "1");
        input.put("status", "compleated"); // typo

        ToolResult result = ToolTestSupport.invoke(update, input, context);
        assertFalse(result.success());
        assertTrue(result.output().contains("Invalid status"));
    }

    // ---- todo_write ----

    @Test
    void todoWriteReplacesAll() {
        TodoWriteTool tool = new TodoWriteTool();
        ObjectNode input = mapper.createObjectNode();
        ArrayNode todos = mapper.createArrayNode();
        ObjectNode item1 = mapper.createObjectNode();
        item1.put("content", "Read code");
        item1.put("status", "completed");
        todos.add(item1);
        ObjectNode item2 = mapper.createObjectNode();
        item2.put("content", "Write test");
        item2.put("status", "in_progress");
        todos.add(item2);
        input.set("todos", todos);

        ToolResult result = ToolTestSupport.invoke(tool, input, context);
        assertTrue(result.success(), result.output());
        assertEquals("0 pending, 1 in_progress, 1 completed", result.output());
        assertEquals(2, session.plan().todos().size());
    }

    @Test
    void todoWriteEmptyClearsAll() {
        session.plan().replaceTodos(List.of(new TodoItem("Old", "pending")));

        TodoWriteTool tool = new TodoWriteTool();
        ObjectNode input = mapper.createObjectNode();
        input.set("todos", mapper.createArrayNode());

        ToolResult result = ToolTestSupport.invoke(tool, input, context);
        assertTrue(result.success());
        assertEquals("0 pending, 0 in_progress, 0 completed", result.output());
        assertTrue(session.plan().todos().isEmpty());
    }

    @Test
    void todoWriteRejectsMissingArray() {
        TodoWriteTool tool = new TodoWriteTool();
        ObjectNode input = mapper.createObjectNode();

        ToolResult result = ToolTestSupport.invoke(tool, input, context);
        assertFalse(result.success());
    }

    @Test
    void todoWriteRejectsEmptyContent() {
        TodoWriteTool tool = new TodoWriteTool();
        ObjectNode input = mapper.createObjectNode();
        ArrayNode todos = mapper.createArrayNode();
        ObjectNode item = mapper.createObjectNode();
        item.put("content", "");
        item.put("status", "pending");
        todos.add(item);
        input.set("todos", todos);

        ToolResult result = ToolTestSupport.invoke(tool, input, context);
        assertFalse(result.success());
    }

    @Test
    void todoWriteRejectsInvalidStatus() {
        TodoWriteTool tool = new TodoWriteTool();
        ObjectNode input = mapper.createObjectNode();
        ArrayNode todos = mapper.createArrayNode();
        ObjectNode item = mapper.createObjectNode();
        item.put("content", "Valid content");
        item.put("status", "done");
        todos.add(item);
        input.set("todos", todos);

        ToolResult result = ToolTestSupport.invoke(tool, input, context);
        assertFalse(result.success());
    }

    // ---- Schema tests ----

    @Test
    void allToolSchemasHaveNameAndProperties() {
        List<Tool<?>> tools = List.of(
                new PlanCreateTool(),
                new PlanGetTool(),
                new PlanListTool(),
                new PlanUpdateTool(),
                new TodoWriteTool());

        for (Tool tool : tools) {
            assertFalse(tool.name().isBlank(), "Tool name must not be blank");
            assertFalse(tool.description().isBlank(), "Tool description must not be blank");
            ObjectNode schema = tool.inputSchema(mapper);
            assertEquals("object", schema.path("type").asText(),
                    "Schema for " + tool.name() + " must be type: object");
            assertTrue(schema.has("properties"), tool.name() + " must have properties");
        }
    }

    @Test
    void planToolsHaveCorrectNames() {
        assertEquals("plan_create", new PlanCreateTool().name());
        assertEquals("plan_get", new PlanGetTool().name());
        assertEquals("plan_list", new PlanListTool().name());
        assertEquals("plan_update", new PlanUpdateTool().name());
        assertEquals("todo_write", new TodoWriteTool().name());
    }
}
