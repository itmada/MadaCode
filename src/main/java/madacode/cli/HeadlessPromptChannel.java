package madacode.cli;

import java.util.List;
import java.util.Optional;

/**
 * Non-interactive prompt channel — always unavailable.
 *
 * <p>Used in headless mode, sub-agents, and tests where no real user
 * interaction is possible. Tools should fail honestly rather than
 * fabricating a "(none selected)" answer.
 */
public final class HeadlessPromptChannel implements UserPromptChannel {

    public static final HeadlessPromptChannel INSTANCE = new HeadlessPromptChannel();

    private HeadlessPromptChannel() {}

    @Override public boolean isAvailable() { return false; }
    @Override public Optional<String> chooseOne(String title, List<ChannelOption> options) { return Optional.empty(); }
    @Override public Optional<List<String>> chooseMany(String title, List<ChannelOption> options) { return Optional.empty(); }
    @Override public Optional<String> freeText(String prompt) { return Optional.empty(); }
    @Override public boolean confirm(String prompt) { return false; }
}
