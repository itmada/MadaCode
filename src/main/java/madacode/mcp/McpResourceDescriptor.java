package madacode.mcp;

public record McpResourceDescriptor(
        String server, String uri, String name, String mimeType, String description) {}
