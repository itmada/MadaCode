package madacode.tool;

import madacode.cli.FakeUserPromptChannel;
import madacode.cli.UnavailablePromptChannel;
import madacode.cli.UserPromptChannel;
import madacode.core.turn.CancellationToken;
import madacode.core.session.ConversationSession;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AskUserQuestionToolTest {

    private AskUserQuestionTool tool;
    private ConversationSession session;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        tool = new AskUserQuestionTool();
        session = new ConversationSession();
        mapper = new ObjectMapper();
    }

    private ToolUseContext ctx(madacode.cli.UserPromptChannel channel) {
        return ctx(channel, CancellationToken.never());
    }

    private ToolUseContext ctx(madacode.cli.UserPromptChannel channel, CancellationToken token) {
        return new ToolUseContext(Path.of(System.getProperty("user.dir")), session,
                0, 1, token, channel);
    }

    private ObjectNode singleFreeTextQuestion(String question) {
        ObjectNode input = mapper.createObjectNode();
        ArrayNode questions = mapper.createArrayNode();
        ObjectNode q = mapper.createObjectNode();
        q.put("question", question);
        q.put("header", "Q");
        questions.add(q);
        input.set("questions", questions);
        return input;
    }

    private ObjectNode singleChoiceQuestion(String question, String... options) {
        ObjectNode input = mapper.createObjectNode();
        ArrayNode questions = mapper.createArrayNode();
        ObjectNode q = mapper.createObjectNode();
        q.put("question", question);
        q.put("header", "Q");
        ArrayNode opts = mapper.createArrayNode();
        for (String opt : options) {
            ObjectNode o = mapper.createObjectNode();
            o.put("label", opt);
            o.put("description", opt);
            opts.add(o);
        }
        q.set("options", opts);
        questions.add(q);
        input.set("questions", questions);
        return input;
    }

    // ---- validation -------------------------------------------------------

    @Test
    void returns_failure_when_questions_array_is_null() {
        ObjectNode input = mapper.createObjectNode();
        ToolResult result = ToolTestSupport.invoke(tool, input, ctx(UnavailablePromptChannel.INSTANCE));
        assertFalse(result.success());
    }

    @Test
    void returns_failure_when_questions_array_is_empty() {
        ObjectNode input = mapper.createObjectNode();
        input.set("questions", mapper.createArrayNode());
        ToolResult result = ToolTestSupport.invoke(tool, input, ctx(UnavailablePromptChannel.INSTANCE));
        assertFalse(result.success());
    }

    @Test
    void returns_failure_when_channel_unavailable() {
        FakeUserPromptChannel unavailable = new FakeUserPromptChannel().setUnavailable();
        ToolResult result = ToolTestSupport.invoke(tool,
                singleFreeTextQuestion("What now?"), ctx(unavailable));
        assertFalse(result.success());
        assertTrue(result.output().toLowerCase().contains("no interactive"),
                "Should mention no interactive channel. Got: " + result.output());
    }

    @Test
    void returns_failure_when_a_question_text_is_blank() {
        ObjectNode input = mapper.createObjectNode();
        ArrayNode questions = mapper.createArrayNode();
        ObjectNode q = mapper.createObjectNode();
        q.put("question", "  ");
        q.put("header", "Q");
        questions.add(q);
        input.set("questions", questions);
        FakeUserPromptChannel channel = new FakeUserPromptChannel();
        ToolResult result = ToolTestSupport.invoke(tool, input, ctx(channel));
        assertFalse(result.success());
    }

    // ---- happy paths ------------------------------------------------------

    @Test
    void propagates_free_text_when_no_options() {
        FakeUserPromptChannel channel = new FakeUserPromptChannel().queueAnswer("My answer");
        ToolResult result = ToolTestSupport.invoke(tool,
                singleFreeTextQuestion("Describe the goal"), ctx(channel));
        assertTrue(result.success());
        assertTrue(result.output().contains("My answer"));
    }

    @Test
    void propagates_chosen_label_for_single_choice() {
        FakeUserPromptChannel channel = new FakeUserPromptChannel().queueAnswer("Beta");
        ToolResult result = ToolTestSupport.invoke(tool,
                singleChoiceQuestion("Pick one", "Alpha", "Beta", "Gamma"), ctx(channel));
        assertTrue(result.success());
        assertTrue(result.output().contains("Beta"));
    }

    @Test
    void propagates_concatenated_labels_for_multi_choice() {
        ObjectNode input = mapper.createObjectNode();
        ArrayNode questions = mapper.createArrayNode();
        ObjectNode q = mapper.createObjectNode();
        q.put("question", "Pick several");
        q.put("header", "Q");
        q.put("multiSelect", true);
        ArrayNode opts = mapper.createArrayNode();
        for (String opt : new String[]{"Alpha", "Beta", "Gamma"}) {
            ObjectNode o = mapper.createObjectNode();
            o.put("label", opt);
            o.put("description", opt);
            opts.add(o);
        }
        q.set("options", opts);
        questions.add(q);
        input.set("questions", questions);

        // FakeUserPromptChannel.chooseMany splits by comma
        FakeUserPromptChannel channel = new FakeUserPromptChannel().queueAnswer("Alpha,Gamma");
        ToolResult result = ToolTestSupport.invoke(tool, input, ctx(channel));
        assertTrue(result.success());
        assertTrue(result.output().contains("Alpha"));
        assertTrue(result.output().contains("Gamma"));
    }

    @Test
    void writes_no_answer_marker_when_channel_returns_empty_without_cancellation() {
        FakeUserPromptChannel channel = new FakeUserPromptChannel().queueCancel();
        ToolResult result = ToolTestSupport.invoke(tool,
                singleFreeTextQuestion("Describe the goal"), ctx(channel));
        assertTrue(result.success());
        assertTrue(result.output().contains("(no answer)"),
                "Blank answer should show (no answer). Got: " + result.output());
    }

    @Test
    void returns_failure_when_prompt_channel_cancels_turn() {
        CancellationToken token = CancellationToken.create();
        UserPromptChannel channel = new UserPromptChannel() {
            @Override public boolean isAvailable() { return true; }
            @Override public Optional<String> chooseOne(String title, List<ChannelOption> options) {
                return Optional.empty();
            }
            @Override public Optional<List<String>> chooseMany(String title, List<ChannelOption> options) {
                return Optional.empty();
            }
            @Override
            public Optional<String> freeText(String prompt) {
                token.cancel("esc");
                return Optional.empty();
            }
            @Override public boolean confirm(String prompt) { return false; }
        };
        ToolResult result = ToolTestSupport.invoke(tool,
                singleFreeTextQuestion("Describe the goal"), ctx(channel, token));
        assertFalse(result.success());
        assertTrue(result.output().contains("(user cancelled)"),
                "Cancelled answer should show (user cancelled). Got: " + result.output());
    }

    @Test
    void handles_multiple_questions_in_sequence() {
        ObjectNode input = mapper.createObjectNode();
        ArrayNode questions = mapper.createArrayNode();
        for (String text : new String[]{"First question?", "Second question?"}) {
            ObjectNode q = mapper.createObjectNode();
            q.put("question", text);
            q.put("header", text.substring(0, 5));
            questions.add(q);
        }
        input.set("questions", questions);

        FakeUserPromptChannel channel = new FakeUserPromptChannel()
                .queueAnswer("Answer 1")
                .queueAnswer("Answer 2");
        ToolResult result = ToolTestSupport.invoke(tool, input, ctx(channel));
        assertTrue(result.success());
        assertTrue(result.output().contains("Answer 1"));
        assertTrue(result.output().contains("Answer 2"));
    }
}
