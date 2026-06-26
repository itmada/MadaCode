package madacode.cli;

import java.util.List;
import java.util.Optional;

/**
 * Abstraction for user interaction during tool execution.
 *
 * <p>Tools like {@code ask_user_question} use this
 * channel instead of reading directly from {@code System.in}, which would
 * conflict with the JLine terminal reader.
 */
public interface UserPromptChannel {

    /** Structured option data for prompt renderers. */
    record ChannelOption(String label, String description) {}

    /**
     * A single question for {@link #askQuestion(QuestionForm)}: a header chip,
     * the question text, an optional progress marker ("1/3"), the options
     * (empty for a free-text-only question), and whether multiple options may
     * be selected.
     */
    record QuestionForm(String header, String question, String progress,
                        List<ChannelOption> options, boolean multiSelect) {
        public QuestionForm {
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /** Whether this channel can actually prompt the user. */
    boolean isAvailable();

    /**
     * Unified question prompt: an option list (single- or multi-select) plus a
     * persistent free-text field. The returned list is the chosen option labels
     * in display order followed by the free text (when non-empty), so a user
     * can pick presets and add a note in one answer.
     *
     * <p>{@code Optional.empty()} means the user cancelled. A present but empty
     * list means the user submitted with no selection and no text. The default
     * implementation returns empty (used by channels that cannot prompt).
     */
    default Optional<List<String>> askQuestion(QuestionForm form) {
        return Optional.empty();
    }

    /** Single-choice prompt. Returns empty if user cancels or channel is unavailable. */
    Optional<String> chooseOne(String title, List<ChannelOption> options);

    /** Multi-choice prompt. Returns empty if user cancels or channel is unavailable. */
    Optional<List<String>> chooseMany(String title, List<ChannelOption> options);

    /** Free-text prompt. Returns empty if user cancels or channel is unavailable. */
    Optional<String> freeText(String prompt);

    /**
     * Sensitive free-text prompt. Returns exactly what the user entered, except
     * blank input is still treated as empty.
     */
    default Optional<String> sensitiveText(String prompt) {
        return freeText(prompt);
    }

    /** Yes/no confirmation. Returns false if user says no or channel is unavailable. */
    boolean confirm(String prompt);
}
