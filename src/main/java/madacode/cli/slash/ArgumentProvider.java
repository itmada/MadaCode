package madacode.cli.slash;

import java.util.List;

/**
 * Provides completions for a slash command's argument position.
 *
 * <p>Returned by {@link SlashCommand#argumentProvider(SlashContext)}.
 * Commands with no argument completions return {@code Optional.empty()}.
 */
public interface ArgumentProvider {

    List<Candidate> candidates(String partialArg);

    record Candidate(String value, String description) {
        public Candidate {
            value = value == null ? "" : value;
            description = description == null ? "" : description;
        }
    }
}
