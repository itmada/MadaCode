package madacode.provider;

import java.net.URI;
import java.util.List;
import java.util.Objects;

public record Provider(
        String name,
        String authToken,
        URI baseUrl,
        String defaultModel,
        List<Model> models
) {
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
