package madacode.cli;

import java.util.List;
import java.util.Optional;

/**
 * Prompt channel used when a tool is intentionally not allowed to ask the user.
 *
 * <p>Sub-agents and unit tests use this channel so tools fail clearly instead
 * of inventing a default answer.
 */
public final class UnavailablePromptChannel implements UserPromptChannel {

    public static final UnavailablePromptChannel INSTANCE = new UnavailablePromptChannel();

    private UnavailablePromptChannel() {}

    @Override public boolean isAvailable() { return false; }
    @Override public Optional<String> chooseOne(String title, List<ChannelOption> options) { return Optional.empty(); }
    @Override public Optional<List<String>> chooseMany(String title, List<ChannelOption> options) { return Optional.empty(); }
    @Override public Optional<String> freeText(String prompt) { return Optional.empty(); }
    @Override public boolean confirm(String prompt) { return false; }
}
