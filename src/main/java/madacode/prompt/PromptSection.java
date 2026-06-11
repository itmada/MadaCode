package madacode.prompt;

import java.util.Optional;

public interface PromptSection {

    Optional<String> render(PromptContext ctx);
}
