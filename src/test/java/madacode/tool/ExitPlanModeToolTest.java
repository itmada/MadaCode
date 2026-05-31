package madacode.tool;

import madacode.cli.FakeUserPromptChannel;
import madacode.cli.UnavailablePromptChannel;
import madacode.core.session.ConversationSession;
import madacode.core.model.MetaEvent;
import madacode.core.session.SessionListener;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExitPlanModeToolTest {

    private ExitPlanModeTool tool;
    private ConversationSession session;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        tool = new ExitPlanModeTool();
        session = new ConversationSession();
        mapper = new ObjectMapper();
    }

    private ToolUseContext ctx(madacode.cli.UserPromptChannel channel) {
        return new ToolUseContext(Path.of(System.getProperty("user.dir")), session,
                0, 1, madacode.core.turn.CancellationToken.never(), channel);
    }

    private ObjectNode input(String summary) {
        ObjectNode n = mapper.createObjectNode();
        if (summary != null) n.put("plan_summary", summary);
        return n;
    }

    private List<MetaEvent> captureEvents() {
        List<MetaEvent> events = new ArrayList<>();
        session.addListener(new SessionListener() {
            @Override public void onMetaEvent(MetaEvent e) { events.add(e); }
        });
        return events;
    }

    // ---- not in plan mode -------------------------------------------------

    @Test
    void returns_failure_when_not_in_plan_mode() {
        ToolResult result = ToolTestSupport.invoke(tool, input(""), ctx(UnavailablePromptChannel.INSTANCE));
        assertFalse(result.success());
        assertTrue(result.output().contains("Not in plan mode"));
    }

    // ---- unavailable prompt channel ---------------------------------------

    @Test
    void unavailable_prompt_channel_keeps_plan_mode_active() {
        session.setPlanMode(true);
        List<MetaEvent> events = captureEvents();

        ToolResult result = ToolTestSupport.invoke(tool, input("Do X then Y"),
                ctx(UnavailablePromptChannel.INSTANCE));

        assertFalse(result.success());
        assertTrue(session.isPlanMode(), "plan mode should stay active");
        assertFalse(events.stream().anyMatch(e -> e instanceof MetaEvent.PlanModeExited),
                "PlanModeExited must not fire without approval");
    }

    // ---- interactive approve ----------------------------------------------

    @Test
    void approved_clears_plan_mode_and_fires_PlanModeExited() {
        session.setPlanMode(true);
        List<MetaEvent> events = captureEvents();

        FakeUserPromptChannel channel = new FakeUserPromptChannel().queueConfirm(true);
        ToolResult result = ToolTestSupport.invoke(tool, input("Do X"), ctx(channel));

        assertTrue(result.success());
        assertFalse(session.isPlanMode());
        assertTrue(events.stream().anyMatch(e -> e instanceof MetaEvent.PlanModeExited));
    }

    // ---- interactive reject — critical regression guards ------------------

    @Test
    void rejected_keeps_plan_mode_active() {
        session.setPlanMode(true);
        FakeUserPromptChannel channel = new FakeUserPromptChannel().queueConfirm(false);
        ToolTestSupport.invoke(tool, input("Do X"), ctx(channel));
        assertTrue(session.isPlanMode(), "plan mode must stay active after rejection");
    }

    @Test
    void rejected_fires_PlanRejected_not_PlanModeExited() {
        session.setPlanMode(true);
        List<MetaEvent> events = captureEvents();
        FakeUserPromptChannel channel = new FakeUserPromptChannel().queueConfirm(false);

        ToolTestSupport.invoke(tool, input("Do X"), ctx(channel));

        assertTrue(events.stream().anyMatch(e -> e instanceof MetaEvent.PlanRejected),
                "PlanRejected must fire on rejection");
        assertFalse(events.stream().anyMatch(e -> e instanceof MetaEvent.PlanModeExited),
                "Rejected flow must NOT fire PlanModeExited (plan mode is still active). "
                + "Events fired: " + events);
    }

    @Test
    void rejected_error_message_does_not_contain_word_again() {
        session.setPlanMode(true);
        FakeUserPromptChannel channel = new FakeUserPromptChannel().queueConfirm(false);

        ToolResult result = ToolTestSupport.invoke(tool, input("Do X"), ctx(channel));

        assertFalse(result.success());
        assertFalse(result.output().toLowerCase().contains("again"),
                "Error message must not coach the AI to retry. Got: " + result.output());
    }

    @Test
    void rejected_error_message_does_not_contain_auto() {
        session.setPlanMode(true);
        FakeUserPromptChannel channel = new FakeUserPromptChannel().queueConfirm(false);

        ToolResult result = ToolTestSupport.invoke(tool, input("Do X"), ctx(channel));

        assertFalse(result.success());
        assertFalse(result.output().toLowerCase().contains("auto"),
                "Rejection message must not mention 'auto'. Got: " + result.output());
    }
}
