package madacode.provider;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderFineGrainedToolStreamingTest {

    private static Provider providerWithBaseUrl(String baseUrl) {
        return new Provider(
                "test",
                "token",
                URI.create(baseUrl),
                "model-x",
                List.of(new Model("model-x", Model.DEFAULT_CONTEXT_WINDOW)));
    }

    @Test
    void firstPartyAnthropicHostSupportsFineGrainedToolStreaming() {
        assertTrue(providerWithBaseUrl("https://api.anthropic.com")
                .supportsFineGrainedToolStreaming());
        assertTrue(providerWithBaseUrl("https://api.anthropic.com/v1")
                .supportsFineGrainedToolStreaming());
        assertTrue(providerWithBaseUrl("https://api-staging.anthropic.com/v1/messages")
                .supportsFineGrainedToolStreaming());
    }

    @Test
    void proxyAndRelayHostsDoNotSupportFineGrainedToolStreaming() {
        assertFalse(providerWithBaseUrl("https://your-provider.example.com")
                .supportsFineGrainedToolStreaming());
        assertFalse(providerWithBaseUrl("https://litellm.internal/v1")
                .supportsFineGrainedToolStreaming());
        // A look-alike host must not be treated as first-party.
        assertFalse(providerWithBaseUrl("https://api.anthropic.com.evil.example/v1")
                .supportsFineGrainedToolStreaming());
    }
}
