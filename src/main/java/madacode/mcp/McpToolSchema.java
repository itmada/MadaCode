package madacode.mcp;

import com.fasterxml.jackson.databind.JsonNode;

public record McpToolSchema(
        String name,
        String description,
        JsonNode inputSchema
) {}
