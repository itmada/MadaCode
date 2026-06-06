package madacode.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ProviderLoader {

    private static final String TEMPLATE = """
            {
              "providers": [
                {
                  "name": "your-provider",
                  "authToken": "YOUR-AUTH-TOKEN",
                  "baseUrl": "https://your-provider.example.com",
                  "defaultModel": "your-model",
                  "models": [
                    { "name": "your-model" }
                  ]
                }
              ]
            }
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;

    public ProviderLoader(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public boolean exists() {
        return Files.isRegularFile(file);
    }

    public List<Provider> load() {
        if (!exists()) {
            createTemplate();
            throw new TemplateCreatedException(
                    "Created template at " + file + ". Edit authToken and run again.");
        }

        JsonNode root;
        try {
            root = mapper.readTree(file.toFile());
        } catch (IOException e) {
            throw new ProviderException("Failed to read " + file + ": " + e.getMessage(), e);
        }

        JsonNode providersNode = root.path("providers");
        if (!providersNode.isArray() || providersNode.isEmpty()) {
            throw new ProviderException("providers.json must contain a non-empty 'providers' array");
        }

        List<Provider> providers = new ArrayList<>();
        int idx = 0;
        for (JsonNode n : providersNode) {
            providers.add(parseProvider(n, "providers[" + idx + "]"));
            idx++;
        }
        return providers;
    }

    public void createTemplate() {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, TEMPLATE);
        } catch (IOException e) {
            throw new ProviderException("Failed to create template " + file + ": " + e.getMessage(), e);
        }
    }

    public void save(List<Provider> providers) {
        if (providers == null || providers.isEmpty()) {
            throw new ProviderException("providers must not be empty");
        }
        Path temp = null;
        boolean moved = false;
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            temp = Files.createTempFile(parent, file.getFileName().toString() + ".", ".tmp");
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(temp.toFile(), serializeProviders(providers));
            moveIntoPlace(temp);
            moved = true;
        } catch (IOException e) {
            throw new ProviderException("Failed to write " + file + ": " + e.getMessage(), e);
        } finally {
            if (!moved && temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void moveIntoPlace(Path temp) throws IOException {
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private JsonNode serializeProviders(List<Provider> providers) {
        var root = mapper.createObjectNode();
        var providersNode = mapper.createArrayNode();
        for (Provider provider : providers) {
            var providerNode = mapper.createObjectNode();
            providerNode.put("name", provider.name());
            providerNode.put("authToken", provider.authToken());
            providerNode.put("baseUrl", provider.baseUrl().toString());
            providerNode.put("defaultModel", provider.defaultModel());
            var modelsNode = mapper.createArrayNode();
            for (Model model : provider.models()) {
                var modelNode = mapper.createObjectNode();
                modelNode.put("name", model.name());
                if (model.contextWindow() != Model.DEFAULT_CONTEXT_WINDOW) {
                    modelNode.put("contextWindow", model.contextWindow());
                }
                modelsNode.add(modelNode);
            }
            providerNode.set("models", modelsNode);
            providersNode.add(providerNode);
        }
        root.set("providers", providersNode);
        return root;
    }

    private Provider parseProvider(JsonNode n, String context) {
        String name = text(n, "name", context);
        // Now we know the name; refine context for nested errors.
        String providerCtx = "provider '" + name + "'";

        String authToken = text(n, "authToken", providerCtx);
        URI baseUrl;
        try {
            baseUrl = URI.create(text(n, "baseUrl", providerCtx));
        } catch (IllegalArgumentException e) {
            throw new ProviderException("Invalid baseUrl in " + providerCtx + ": " + e.getMessage());
        }
        String defaultModel = text(n, "defaultModel", providerCtx);

        JsonNode modelsNode = n.path("models");
        if (!modelsNode.isArray() || modelsNode.isEmpty()) {
            throw new ProviderException(providerCtx + " must have non-empty 'models' array");
        }
        List<Model> models = new ArrayList<>();
        int idx = 0;
        for (JsonNode m : modelsNode) {
            models.add(parseModel(m, providerCtx + " models[" + idx + "]"));
            idx++;
        }

        try {
            return new Provider(name, authToken, baseUrl, defaultModel, models);
        } catch (IllegalArgumentException e) {
            throw new ProviderException(providerCtx + ": " + e.getMessage());
        }
    }

    private Model parseModel(JsonNode n, String context) {
        String name = text(n, "name", context);
        String modelCtx = context + " ('" + name + "')";
        int contextWindow = parseContextWindow(n.get("contextWindow"), modelCtx);
        try {
            return new Model(name, contextWindow);
        } catch (IllegalArgumentException e) {
            throw new ProviderException(modelCtx + ": " + e.getMessage());
        }
    }

    private int parseContextWindow(JsonNode node, String context) {
        if (node == null || node.isNull()) {
            return Model.DEFAULT_CONTEXT_WINDOW;
        }
        if (node.isInt() || node.isLong()) {
            long value = node.asLong();
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new ProviderException("contextWindow in " + context + " overflows int: " + value);
            }
            return (int) value;
        }
        if (node.isTextual()) {
            return parseSizeString(node.asText(), context);
        }
        throw new ProviderException("contextWindow in " + context
                + " must be number or size string (e.g. '1M', '256K')");
    }

    private int parseSizeString(String s, String context) {
        String trimmed = s.trim().toUpperCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            throw new ProviderException("contextWindow string empty in " + context);
        }
        int multiplier = 1;
        String numeric = trimmed;
        if (trimmed.endsWith("K")) {
            multiplier = 1_000;
            numeric = trimmed.substring(0, trimmed.length() - 1).trim();
        } else if (trimmed.endsWith("M")) {
            multiplier = 1_000_000;
            numeric = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        try {
            double value = Double.parseDouble(numeric);
            return (int) Math.round(value * multiplier);
        } catch (NumberFormatException e) {
            throw new ProviderException("Invalid contextWindow '" + s + "' in " + context);
        }
    }

    private static String text(JsonNode obj, String field, String context) {
        JsonNode v = obj.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) {
            throw new ProviderException("Missing or blank '" + field + "' in " + context);
        }
        return v.asText();
    }
}
