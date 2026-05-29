package madacode.provider;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProviderTest {

    private static Provider build(URI baseUrl) {
        return new Provider("test", "tok", baseUrl, "m1",
                List.of(new Model("m1", Model.DEFAULT_CONTEXT_WINDOW)));
    }

    @Test
    void messagesUrlAppendsV1MessagesToPlainBase() {
        Provider p = build(URI.create("https://api.anthropic.com"));
        assertEquals("https://api.anthropic.com/v1/messages",
                p.messagesUrl().toString());
    }

    @Test
    void messagesUrlStripsTrailingSlash() {
        Provider p = build(URI.create("https://api.anthropic.com/"));
        assertEquals("https://api.anthropic.com/v1/messages",
                p.messagesUrl().toString());
    }

    @Test
    void messagesUrlAcceptsBaseEndingInV1() {
        // Some providers' docs tell users to include /v1 in the base.
        Provider p = build(URI.create("https://api.example.com/v1"));
        assertEquals("https://api.example.com/v1/messages",
                p.messagesUrl().toString());
    }

    @Test
    void messagesUrlAcceptsFullPathBase() {
        // If the user writes the full endpoint, don't append anything.
        Provider p = build(URI.create("https://api.example.com/v1/messages"));
        assertEquals("https://api.example.com/v1/messages",
                p.messagesUrl().toString());
    }

    @Test
    void messagesUrlWorksWithNestedPath() {
        // DeepSeek-style baseUrl with provider-specific prefix.
        Provider p = build(URI.create("https://api.deepseek.com/anthropic"));
        assertEquals("https://api.deepseek.com/anthropic/v1/messages",
                p.messagesUrl().toString());
    }
}
