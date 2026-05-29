package madacode.mcp;

import java.util.List;

public record McpResourceContent(List<Item> items) {
    public McpResourceContent {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public record Item(String uri, String mimeType, String text, String blobBase64) {
        public boolean hasText() { return text != null; }
        public boolean hasBlob() { return blobBase64 != null; }
    }
}
