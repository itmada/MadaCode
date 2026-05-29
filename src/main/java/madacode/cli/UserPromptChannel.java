package madacode.cli;

import java.util.List;
import java.util.Optional;

/**
 * Abstraction for user interaction during tool execution.
 *
 * <p>Tools like {@code ask_user_question} and {@code exit_plan_mode} use this
 * channel instead of reading directly from {@code System.in}, which would
 * conflict with the JLine terminal reader.
 */
public interface UserPromptChannel {

    /** Structured option data for prompt renderers. */
    record ChannelOption(String label, String description) {}

    /** Whether this channel can actually prompt the user. */
    boolean isAvailable();

    /** Single-choice prompt. Returns empty if user cancels or channel is unavailable. */
    Optional<String> chooseOne(String title, List<ChannelOption> options);

    /** Multi-choice prompt. Returns empty if user cancels or channel is unavailable. */
    Optional<List<String>> chooseMany(String title, List<ChannelOption> options);

    /** Free-text prompt. Returns empty if user cancels or channel is unavailable. */
    Optional<String> freeText(String prompt);

    /** Yes/no confirmation. Returns false if user says no or channel is unavailable. */
    boolean confirm(String prompt);
}
