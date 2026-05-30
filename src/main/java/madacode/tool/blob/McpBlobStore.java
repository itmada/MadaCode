package madacode.tool.blob;

import java.io.IOException;
import java.nio.file.Path;

public sealed interface McpBlobStore permits FilesystemBlobStore {
    record Persisted(Path path, long bytes, String mimeType) {}
    Persisted persist(byte[] data, String mimeType) throws IOException;
}
