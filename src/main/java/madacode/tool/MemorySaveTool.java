package madacode.tool;

import madacode.core.ToolResult;
import madacode.core.ToolUseContext;
import madacode.memory.MemoryFile;
import madacode.memory.MemoryStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Locale;

public class MemorySaveTool implements Tool<MemorySaveTool.Input> {

    public record Input(
            String name,
            String description,
            String type,
            String body,
            String file) {}

    private final MemoryStore store;

    public MemorySaveTool(MemoryStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "memory_save";
    }

    @Override
    public String description() {
        return "Saves a long-term memory entry. Use when the user shares "
                + "non-obvious preferences, facts about themselves, or "
                + "project context worth remembering across sessions.";
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
        properties.set("name", ToolSchemas.stringProperty(mapper, "Short title for this memory"));
        properties.set("description", ToolSchemas.stringProperty(mapper,
                "One-line summary used to decide relevance in future conversations"));
        properties.set("body", ToolSchemas.stringProperty(mapper, "Full memory content"));
        ObjectNode typeProp = mapper.createObjectNode();
        typeProp.put("type", "string");
        typeProp.put("description", "Memory type: user, feedback, project, or reference");
        ArrayNode enumValues = mapper.createArrayNode();
        enumValues.add("user");
        enumValues.add("feedback");
        enumValues.add("project");
        enumValues.add("reference");
        typeProp.set("enum", enumValues);
        properties.set("type", typeProp);
        properties.set("file", ToolSchemas.stringProperty(mapper,
                "Optional filename (e.g. feedback_testing.md). Generated if omitted."));
        return ToolSchemas.objectSchema(mapper, properties, "name", "description", "type", "body");
    }

    @Override
    public ToolResult execute(Input input, ToolUseContext context) {
        String name = input.name() == null ? "" : input.name();
        String description = input.description() == null ? "" : input.description();
        String typeStr = input.type() == null ? "" : input.type();
        String body = input.body() == null ? "" : input.body();
        String file = input.file() == null ? "" : input.file();

        MemoryFile.MemoryType type = MemoryFile.MemoryType.parse(typeStr);
        if (type == null) {
            return new ToolResult(name(), false,
                    "Invalid type '" + typeStr + "'. Must be user, feedback, project, or reference.");
        }

        String filename = file.isBlank()
                ? type.slug() + "_" + slugify(name) + ".md"
                : file;

        MemoryFile mf = new MemoryFile(name, description, type, body, null);
        store.write(mf, filename);

        return new ToolResult(name(), true,
                "Saved memory '" + name + "' to " + filename
                        + " (type=" + type.slug() + ")");
    }

    private String slugify(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }
}
