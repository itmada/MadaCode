package madacode.tool.blob;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class FilesystemBlobStore implements McpBlobStore {

    private final Path root;

    public FilesystemBlobStore(Path root) {
        this.root = root;
    }

    @Override
    public McpBlobStore.Persisted persist(byte[] data, String mimeType) throws IOException {
        Files.createDirectories(root);
        String stem = sha256Hex16(data);
        String ext = extensionFor(mimeType);
        Path target = root.resolve(stem + ext);

        if (Files.exists(target) && Files.size(target) == data.length) {
            return new McpBlobStore.Persisted(target.toAbsolutePath(), data.length, mimeType);
        }

        Path tmp = root.resolve(stem + ext + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        return new McpBlobStore.Persisted(target.toAbsolutePath(), data.length, mimeType);
    }

    private static String sha256Hex16(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String extensionFor(String mimeType) {
        if (mimeType == null) return ".bin";
        return switch (mimeType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            case "application/pdf" -> ".pdf";
            case "application/json" -> ".json";
            case "text/plain" -> ".txt";
            case "text/markdown" -> ".md";
            case "text/html" -> ".html";
            default -> ".bin";
        };
    }
}
