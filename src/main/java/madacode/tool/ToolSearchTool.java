package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.engine.ToolUseContext;
import madacode.core.model.ToolResult;
import madacode.longrunning.LongRunningToolPolicy;
import madacode.util.ToolNameNormalizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class ToolSearchTool implements Tool<ToolSearchTool.Input> {

    public static final String NAME = "tool_search";

    private static final int DEFAULT_MAX_RESULTS = 8;
    private static final int HARD_MAX_RESULTS = 20;

    private final ToolRegistry registry;
    private final ObjectMapper mapper;

    public record Input(String query, Integer max_results) {}

    public ToolSearchTool(ToolRegistry registry) {
        this(registry, new ObjectMapper());
    }

    ToolSearchTool(ToolRegistry registry, ObjectMapper mapper) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Search and load deferred tool definitions. Use this when a needed tool is not "
                + "currently available, or when you need the schema for optional tools such as "
                + "MCP, web, agent, skill, memory, provider, or long-running workflow tools.";
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public boolean isReadOnly() {
        return false;
    }

    @Override
    public ObjectNode inputSchema(ObjectMapper mapper) {
        ObjectNode properties = mapper.createObjectNode();
        properties.set("query", ToolSchemas.stringProperty(mapper,
                "Search terms, or select:<tool1>,<tool2> to load exact tool names."));
        properties.set("max_results", ToolSchemas.integerProperty(mapper,
                "Maximum number of matching tools to load. Defaults to 8.", 1, HARD_MAX_RESULTS));
        return ToolSchemas.objectSchema(mapper, properties, "query");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        String query = input.query() == null ? "" : input.query().strip();
        if (query.isBlank()) {
            return new ToolResult(name(), false,
                    "query is required. Use keywords such as \"mcp github\" or select:<tool>.");
        }

        int maxResults = input.max_results() == null
                ? DEFAULT_MAX_RESULTS
                : Math.max(1, Math.min(input.max_results(), HARD_MAX_RESULTS));

        SearchResult result = query.toLowerCase(Locale.ROOT).startsWith("select:")
                ? exactMatches(query.substring(query.indexOf(':') + 1), context)
                : keywordMatches(query, maxResults, context);

        if (result.loaded().isEmpty() && result.notes().isEmpty()) {
            return new ToolResult(name(), true,
                    "No deferred tools matched \"" + query + "\". Try broader keywords or select:<exact_tool_name>.");
        }

        List<String> loaded = result.loaded().stream().map(Tool::name).toList();
        if (context != null) {
            context.session().loadDeferredTools(loaded);
        }

        StringBuilder out = new StringBuilder();
        if (!result.loaded().isEmpty()) {
            out.append("Loaded deferred tools for the next model request:\n");
            for (Tool<?> tool : result.loaded()) {
                appendTool(out, tool);
            }
        }
        if (!result.notes().isEmpty()) {
            if (!out.isEmpty()) {
                out.append("\n");
            }
            out.append("Other requested tools:\n");
            for (String note : result.notes()) {
                out.append("- ").append(note).append("\n");
            }
        }
        if (!result.loaded().isEmpty()) {
            out.append("\nThese newly loaded tools will be callable after this tool result is processed.");
        }
        return new ToolResult(name(), true, out.toString());
    }

    private SearchResult exactMatches(String rawNames, ToolUseContext context) {
        Set<String> requested = new LinkedHashSet<>();
        for (String raw : rawNames.split(",")) {
            String name = raw.strip();
            if (!name.isBlank()) {
                requested.add(name);
            }
        }
        List<Tool<?>> loaded = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        for (String requestedName : requested) {
            Tool<?> tool = registry.find(requestedName).orElse(null);
            if (tool == null) {
                notes.add(requestedName + ": not found");
            } else if (ToolVisibility.isAlwaysVisible(tool.name())) {
                notes.add(tool.name() + ": already always visible");
            } else if (context != null && context.session().loadedDeferredTools().contains(tool.name())) {
                notes.add(tool.name() + ": already loaded");
            } else if (!LongRunningToolPolicy.isToolVisible(tool, context == null ? null : context.session())) {
                notes.add(tool.name() + ": not available in the current session state");
            } else {
                loaded.add(tool);
            }
        }
        return new SearchResult(loaded, notes);
    }

    private SearchResult keywordMatches(String query, int maxResults, ToolUseContext context) {
        String[] terms = query.toLowerCase(Locale.ROOT).split("\\s+");
        List<Tool<?>> loaded = registry.tools().stream()
                .filter(tool -> isLoadable(tool, context))
                .map(tool -> new ScoredTool(tool, score(tool, terms)))
                .filter(scored -> scored.score() > 0)
                .sorted(Comparator.comparingInt(ScoredTool::score).reversed()
                        .thenComparing(scored -> scored.tool().name()))
                .limit(maxResults)
                .map(ScoredTool::tool)
                .toList();
        return new SearchResult(loaded, List.of());
    }

    private boolean isLoadable(Tool<?> tool, ToolUseContext context) {
        if (tool == null) {
            return false;
        }
        if (ToolVisibility.isAlwaysVisible(tool.name())) {
            return false;
        }
        if (context != null && context.session().loadedDeferredTools().contains(tool.name())) {
            return false;
        }
        return LongRunningToolPolicy.isToolVisible(tool, context == null ? null : context.session());
    }

    private static int score(Tool<?> tool, String[] terms) {
        String name = tool.name();
        String normalizedName = ToolNameNormalizer.normalize(name);
        String haystack = (name + " " + normalizedName + " " + nullToEmpty(tool.description()))
                .toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (term.isBlank()) {
                continue;
            }
            if (name.equalsIgnoreCase(term) || normalizedName.equals(term)) {
                score += 100;
            } else if (name.toLowerCase(Locale.ROOT).contains(term)) {
                score += 30;
            } else if (haystack.contains(term)) {
                score += 10;
            }
        }
        return score;
    }

    private String schemaJson(Tool<?> tool) {
        try {
            return mapper.writeValueAsString(tool.inputSchema(mapper));
        } catch (Exception e) {
            return "{\"type\":\"object\",\"description\":\"Failed to render schema: "
                    + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }

    private void appendTool(StringBuilder out, Tool<?> tool) {
        out.append("\n<tool>\n");
        out.append("name: ").append(tool.name()).append("\n");
        out.append("description: ").append(nullToEmpty(tool.description())).append("\n");
        out.append("input_schema: ").append(schemaJson(tool)).append("\n");
        out.append("</tool>\n");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private record ScoredTool(Tool<?> tool, int score) {}

    private record SearchResult(List<Tool<?>> loaded, List<String> notes) {}
}
