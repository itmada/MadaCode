package madacode.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public record WorkspaceIdentity(Path projectDir, String key) {

    private static final int HASH_LENGTH = 12;

    public WorkspaceIdentity {
        projectDir = Objects.requireNonNull(projectDir, "projectDir")
                .toAbsolutePath()
                .normalize();
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("workspace key must not be blank");
        }
        key = key.strip();
    }

    public static WorkspaceIdentity from(Path projectDir) {
        Path normalized = normalizeProjectDir(projectDir);
        String raw = normalized.toString();
        String sanitized = raw.replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        if (sanitized.isBlank()) {
            sanitized = "workspace";
        }
        return new WorkspaceIdentity(normalized, sanitized + "-" + sha256Prefix(raw));
    }

    private static Path normalizeProjectDir(Path projectDir) {
        Path absolute = Objects.requireNonNull(projectDir, "projectDir")
                .toAbsolutePath()
                .normalize();
        try {
            return absolute.toRealPath().normalize();
        } catch (IOException ignored) {
            return absolute;
        }
    }

    private static String sha256Prefix(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
