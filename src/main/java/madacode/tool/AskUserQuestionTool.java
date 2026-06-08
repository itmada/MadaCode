package madacode.tool;

import madacode.cli.UserPromptChannel;
import madacode.core.model.ToolResult;
import madacode.core.engine.ToolUseContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AskUserQuestionTool implements Tool<AskUserQuestionTool.Input> {

    public record Input(List<Question> questions) {}

    public record Question(
            String question,
            String header,
            List<Option> options,
            Boolean multiSelect) {}

    public record Option(String label, String description) {}

    @Override
    public String name() {
        return "ask_user_question";
    }

    @Override
    public String description() {
        return "Ask the user one or more clarifying questions. "
                + "Use only when a required decision cannot be inferred safely after investigation. "
                + "Ask specific questions with concrete tradeoffs. "
                + "If recommending an option, put it first and mark it as recommended. "
                + "Supports single-choice, multi-select, and free-text (no options) questions.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode optionSchema = mapper.createObjectNode();
        optionSchema.put("type", "object");
        ObjectNode optionProps = mapper.createObjectNode();
        optionProps.set("label", ToolSchemas.stringProperty(mapper, "Display text (1-5 words)"));
        optionProps.set("description", ToolSchemas.stringProperty(mapper, "What this option means or implies"));
        optionSchema.set("properties", optionProps);

        ObjectNode questionSchema = mapper.createObjectNode();
        questionSchema.put("type", "object");
        ObjectNode questionProps = mapper.createObjectNode();
        questionProps.set("question", ToolSchemas.stringProperty(mapper, "The complete question to ask"));
        questionProps.set("header", ToolSchemas.stringProperty(mapper, "Short label (max 25 chars). e.g. 'Auth method', 'Library'"));
        questionProps.set("options", ToolSchemas.arrayProperty(mapper,
                "2-4 choices (omit for free-text)", optionSchema));
        questionProps.set("multiSelect", ToolSchemas.booleanProperty(mapper,
                "Allow multiple selections (default false)"));
        questionSchema.set("properties", questionProps);
        ArrayNode qRequired = mapper.createArrayNode();
        qRequired.add("question");
        qRequired.add("header");
        questionSchema.set("required", qRequired);

        ObjectNode properties = mapper.createObjectNode();
        properties.set("questions", ToolSchemas.arrayProperty(mapper,
                "1-4 questions to ask", questionSchema));
        return ToolSchemas.objectSchema(mapper, properties, "questions");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        List<Question> questions = input.questions();
        if (questions == null || questions.isEmpty()) {
            return new ToolResult(name(), false, "questions array is required");
        }

        UserPromptChannel channel = context.userPrompts();
        if (!channel.isAvailable()) {
            return new ToolResult(name(), false,
                    "No interactive prompt channel available. Cannot ask user questions.");
        }

        Map<String, String> answersMap = new LinkedHashMap<>();
        boolean cancelled = false;

        for (int qi = 0; qi < questions.size(); qi++) {
            Question q = questions.get(qi);
            String question = q.question() == null ? "" : q.question();
            String header = q.header() == null || q.header().isBlank()
                    ? question
                    : q.header();
            boolean multi = Boolean.TRUE.equals(q.multiSelect());
            List<Option> options = q.options();
            boolean hasOptions = options != null && !options.isEmpty();

            if (question.isBlank()) {
                return new ToolResult(name(), false, "Question " + (qi + 1) + " has empty question text");
            }

            Optional<String> answer;
            if (hasOptions) {
                String title = buildChoiceTitle(header, question, qi + 1, questions.size());
                List<UserPromptChannel.ChannelOption> channelOptions = buildChannelOptions(options);
                if (multi) {
                    var selected = channel.chooseMany(title, channelOptions);
                    if (selected.isEmpty()) {
                        cancelled = cancelled || context.cancellationToken().isCancelled();
                        answersMap.put(question, emptyAnswerMarker(context));
                    } else {
                        answersMap.put(question, String.join(", ", selected.get()));
                    }
                } else {
                    answer = channel.chooseOne(title, channelOptions);
                    if (answer.isEmpty()) {
                        cancelled = cancelled || context.cancellationToken().isCancelled();
                    }
                    answersMap.put(question, answer.orElse(emptyAnswerMarker(context)));
                }
            } else {
                String prompt = buildFreeTextPrompt(header, question, qi + 1, questions.size());
                answer = channel.freeText(prompt);
                if (answer.isEmpty()) {
                    cancelled = cancelled || context.cancellationToken().isCancelled();
                }
                answersMap.put(question, answer.orElse(emptyAnswerMarker(context)));
            }
            if (context.cancellationToken().isCancelled()) {
                cancelled = true;
                break;
            }
        }

        StringBuilder result = new StringBuilder();
        result.append("Answers collected:\n");
        for (var entry : answersMap.entrySet()) {
            result.append("- ").append(entry.getKey()).append("\n");
            result.append("  → ").append(entry.getValue()).append("\n");
        }
        return new ToolResult(name(), !cancelled, result.toString().stripTrailing());
    }

    private static List<UserPromptChannel.ChannelOption> buildChannelOptions(List<Option> options) {
        List<UserPromptChannel.ChannelOption> channelOptions = new ArrayList<>();
        for (Option opt : options) {
            String label = opt.label() == null || opt.label().isBlank()
                    ? "Option " + (channelOptions.size() + 1)
                    : opt.label();
            String description = opt.description() == null ? "" : opt.description();
            channelOptions.add(new UserPromptChannel.ChannelOption(label, description));
        }
        return channelOptions;
    }

    private String buildChoiceTitle(String header, String question, int number, int total) {
        return "Question " + number + "/" + total + " — [" + header + "] " + question;
    }

    private String buildFreeTextPrompt(String header, String question, int number, int total) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n  ── Question ").append(number).append("/").append(total).append(" ──\n");
        sb.append("  [").append(header).append("] ").append(question).append("\n\n");
        sb.append("  Your answer: ");
        return sb.toString();
    }

    private static String emptyAnswerMarker(ToolUseContext context) {
        return context.cancellationToken().isCancelled() ? "(user cancelled)" : "(no answer)";
    }
}
