package madacode.tool;

import java.nio.file.Path;

public final class MadaPaths {
    private MadaPaths() {}

    public static Path home() {
        return Path.of(System.getProperty("user.home"), ".mada");
    }

    public static Path blobsDir() {
        return home().resolve("blobs");
    }
}
