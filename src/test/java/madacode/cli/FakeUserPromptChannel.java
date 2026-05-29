package madacode.cli;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

/**
 * Test-only configurable UserPromptChannel backed by pre-loaded answer queues.
 */
public final class FakeUserPromptChannel implements UserPromptChannel {

    private final Queue<Optional<String>> answers = new ArrayDeque<>();
    private final Queue<Boolean> confirms = new ArrayDeque<>();
    private boolean available = true;

    public FakeUserPromptChannel queueAnswer(String answer) {
        answers.add(Optional.ofNullable(answer));
        return this;
    }

    public FakeUserPromptChannel queueCancel() {
        answers.add(Optional.empty());
        return this;
    }

    public FakeUserPromptChannel queueConfirm(boolean value) {
        confirms.add(value);
        return this;
    }

    public FakeUserPromptChannel setUnavailable() {
        available = false;
        return this;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public Optional<String> chooseOne(String title, List<ChannelOption> options) {
        return answers.isEmpty() ? Optional.empty() : answers.poll();
    }

    @Override
    public Optional<List<String>> chooseMany(String title, List<ChannelOption> options) {
        Optional<String> a = answers.isEmpty() ? Optional.empty() : answers.poll();
        return a.map(s -> Arrays.asList(s.split(",")));
    }

    @Override
    public Optional<String> freeText(String prompt) {
        return answers.isEmpty() ? Optional.empty() : answers.poll();
    }

    @Override
    public boolean confirm(String prompt) {
        return !confirms.isEmpty() && confirms.poll();
    }
}
