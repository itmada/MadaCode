package madacode.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

public class McpResourceClient {

    private static final int TIMEOUT_SECONDS = 30;

    private final McpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public McpResourceClient(McpClient client) {
        this.client = client;
    }

    public List<McpResourceDescriptor> listResources() throws McpException {
        JsonNode result = client.sendRequest("resources/list", mapper.createObjectNode(), TIMEOUT_SECONDS);
        JsonNode resources = result.path("resources");
        if (!resources.isArray()) return List.of();

        List<McpResourceDescriptor> list = new ArrayList<>();
        for (JsonNode r : resources) {
            String uri = r.path("uri").asText(null);
            if (uri == null) continue;
            String name = r.has("name") ? r.get("name").asText(null) : null;
            String mimeType = r.has("mimeType") ? r.get("mimeType").asText(null) : null;
            String description = r.has("description") ? r.get("description").asText(null) : null;
            list.add(new McpResourceDescriptor(null, uri, name, mimeType, description));
        }
        return List.copyOf(list);
    }

    public McpResourceContent readResource(String uri) throws McpException {
        ObjectNode params = mapper.createObjectNode();
        params.put("uri", uri);
        JsonNode result = client.sendRequest("resources/read", params, TIMEOUT_SECONDS);
        JsonNode contents = result.path("contents");
        if (!contents.isArray()) return new McpResourceContent(List.of());

        List<McpResourceContent.Item> items = new ArrayList<>();
        for (JsonNode c : contents) {
            String itemUri = c.path("uri").asText(uri);
            String mimeType = c.has("mimeType") ? c.get("mimeType").asText(null) : null;
            if (c.has("text")) {
                items.add(new McpResourceContent.Item(itemUri, mimeType, c.get("text").asText(), null));
            } else if (c.has("blob")) {
                items.add(new McpResourceContent.Item(itemUri, mimeType, null, c.get("blob").asText()));
            } else {
                items.add(new McpResourceContent.Item(itemUri, mimeType, null, null));
            }
        }
        return new McpResourceContent(items);
    }
}
