package madacode.tool;

import madacode.cli.UserPromptChannel;
import madacode.core.ToolResult;
import madacode.core.ToolUseContext;
import madacode.provider.Model;
import madacode.provider.Provider;
import madacode.provider.ProviderException;
import madacode.provider.ProviderLoader;
import madacode.provider.ProviderRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class AddProviderTool implements Tool<AddProviderTool.Input> {

    /** authToken 故意不在入参里——由工具直接向用户索取。 */
    public record Input(String name, String baseUrl, String defaultModel, List<String> models) {}

    private final ProviderRegistry registry;
    private final ProviderLoader loader;

    public AddProviderTool(ProviderRegistry registry, ProviderLoader loader) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    @Override public String name() { return "add_provider"; }

    @Override
    public String description() {
        return "Add a new model provider for the user. Call this only after you have "
             + "explicit provider details from the user. If the user has not already "
             + "provided them in the conversation, first use ask_user_question with free-text "
             + "questions only: omit options and do not use single-choice or multi-select. "
             + "Collect these non-secret fields as direct text input from the user: provider "
             + "name, base URL (must be http/https), default model id, and available model ids. "
             + "Do not guess missing values. "
             + "Model ids should be separated with spaces, not commas. Convert the available "
             + "model ids into the 'models' array; if the user only has one model, pass that "
             + "model or omit 'models' to default to [defaultModel]. "
             + "Never ask for, include, summarize, or pass an auth token yourself: this tool "
             + "will prompt the user locally for the token during execution so the token does "
             + "not enter the model context. The new provider is added and persisted but does "
             + "not become active; tell the user to run /provider to switch.";
    }

    @Override public Class<Input> inputType() { return Input.class; }

    @Override public boolean isReadOnly() { return false; }   // 改 registry + 写文件 → 走权限门

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode props = mapper.createObjectNode();
        props.set("name", ToolSchemas.stringProperty(mapper, "Unique provider name (not 'reset')"));
        props.set("baseUrl", ToolSchemas.stringProperty(mapper, "Provider base URL (http or https)"));
        props.set("defaultModel", ToolSchemas.stringProperty(mapper, "Default model id for this provider"));
        props.set("models", ToolSchemas.arrayProperty(mapper,
                "Available model ids (defaults to [defaultModel] if omitted)",
                ToolSchemas.stringProperty(mapper, "Model id")));
        return ToolSchemas.objectSchema(mapper, props, "name", "baseUrl", "defaultModel");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        // 1. 基础字段校验
        String name = trim(input == null ? null : input.name());
        String baseUrl = trim(input == null ? null : input.baseUrl());
        String defaultModel = trim(input == null ? null : input.defaultModel());
        if (name.isBlank())          return fail("name is required");
        if (defaultModel.isBlank())  return fail("defaultModel is required");

        URI uri = parseHttpUrl(baseUrl);
        if (uri == null) return fail("baseUrl must be a valid http or https URL");

        // 2. 向用户索取 token（密钥不进模型）
        UserPromptChannel channel = context.userPrompts();
        if (!channel.isAvailable()) {
            return fail("No interactive prompt channel available; cannot collect auth token.");
        }
        Optional<String> token = channel.sensitiveText(
                "Paste auth token for provider '" + name + "': ");
        if (token.isEmpty() || token.get().isBlank()) {
            return fail("Auth token entry cancelled or empty; provider not added.");
        }
        String authToken = token.get();

        // 3. 构造 models（defaultModel 去重置顶），再构造 Provider（强校验）
        List<Model> models = buildModels(defaultModel, input.models());
        Provider provider;
        try {
            provider = new Provider(name, authToken, uri, defaultModel, models);
        } catch (IllegalArgumentException e) {
            return fail("Invalid provider: " + e.getMessage());
        }

        // 4. 加入内存 registry（reserved/重名校验）
        try {
            registry.addProvider(provider);
        } catch (ProviderException e) {
            return fail(e.getMessage());
        }

        // 5. 持久化全集；失败则回滚内存，保证内存与磁盘一致
        try {
            loader.save(registry.all());
        } catch (RuntimeException e) {
            try { registry.removeProvider(name); } catch (RuntimeException ignored) {}
            return fail("Failed to persist provider configuration: " + e.getMessage());
        }

        return new ToolResult(name(), true,
                "Added provider '" + name + "' with " + models.size()
              + " model(s); default '" + defaultModel + "'. "
              + "Saved to " + loader.file() + ". Run /provider to switch to it.");
    }

    private ToolResult fail(String msg) { return new ToolResult(name(), false, msg); }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static URI parseHttpUrl(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null) return null;
            if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) return null;
            return uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** defaultModel 置顶并去重；models 入参中的每个字符串按空白拆分；contextWindow 用默认值。 */
    private static List<Model> buildModels(String defaultModel, List<String> extra) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(defaultModel);
        if (extra != null) {
            for (String m : extra) {
                if (m == null || m.isBlank()) {
                    continue;
                }
                for (String id : m.trim().split("\\s+")) {
                    if (!id.isBlank()) {
                        ids.add(id);
                    }
                }
            }
        }
        List<Model> models = new ArrayList<>(ids.size());
        for (String id : ids) models.add(new Model(id, Model.DEFAULT_CONTEXT_WINDOW));
        return models;
    }
}
