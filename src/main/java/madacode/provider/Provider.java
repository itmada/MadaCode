package madacode.provider;

import java.net.URI;
import java.util.List;
import java.util.Objects;

public record Provider(
        String name,
        String authToken,
        URI baseUrl,
        String defaultModel,
        List<Model> models,
        boolean supportsPromptCaching
) {
    public Provider(String name, String authToken, URI baseUrl, String defaultModel, List<Model> models) {
        this(name, authToken, baseUrl, defaultModel, models, false);
    }

    public Provider {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(authToken, "authToken");
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(defaultModel, "defaultModel");
        models = List.copyOf(Objects.requireNonNull(models, "models"));

        if (name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        if (authToken.isBlank()) throw new IllegalArgumentException("authToken must not be blank");

        String scheme = baseUrl.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("baseUrl must use http or https: " + baseUrl);
        }
        if (models.isEmpty()) {
            throw new IllegalArgumentException("provider '" + name + "' must declare at least one model");
        }
        if (models.stream().noneMatch(m -> m.name().equals(defaultModel))) {
            throw new IllegalArgumentException(
                    "defaultModel '" + defaultModel + "' not in models list of provider '" + name + "'");
        }
    }

    /**
     * Whether this endpoint correctly implements fine-grained tool streaming
     * (FGTS): the {@code fine-grained-tool-streaming} beta plus the per-tool
     * {@code eager_input_streaming} field.
     *
     * <p>FGTS is only properly implemented by the first-party Anthropic API.
     * Proxies/relays (LiteLLM and similar) and Bedrock/Vertex either reject the
     * field with a 400 or silently accept it and then stop emitting
     * {@code input_json_delta} events for large tool inputs — producing empty
     * tool arguments ({@code {}}) on big {@code bash}/{@code write} calls. So we
     * gate FGTS to first-party hosts, mirroring the upstream Claude Code
     * {@code isFirstPartyAnthropicBaseUrl()} check.
     *
     * @see #isFirstPartyHost(String)
     */
    public boolean supportsFineGrainedToolStreaming() {
        String host = baseUrl.getHost();
        return host != null && isFirstPartyHost(host);
    }

    private static boolean isFirstPartyHost(String host) {
        String h = host.toLowerCase(java.util.Locale.ROOT);
        return h.equals("api.anthropic.com") || h.equals("api-staging.anthropic.com");
    }

    /**
     * Resolves the Anthropic Messages endpoint from {@link #baseUrl}.
     *
     * <p>Tolerates three common ways users write {@code baseUrl}:
     * <ul>
     *   <li>{@code https://api.anthropic.com} → appends {@code /v1/messages}</li>
     *   <li>{@code https://api.example.com/v1} → appends {@code /messages}</li>
     *   <li>{@code https://api.example.com/v1/messages} → used as-is</li>
     * </ul>
     * Trailing slash is stripped before matching.
     */
    public URI messagesUrl() {
        String normalized = baseUrl.toString();
        if (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.endsWith("/v1/messages")) {
            return URI.create(normalized);
        }
        if (normalized.endsWith("/v1")) {
            return URI.create(normalized + "/messages");
        }
        return URI.create(normalized + "/v1/messages");
    }
}
