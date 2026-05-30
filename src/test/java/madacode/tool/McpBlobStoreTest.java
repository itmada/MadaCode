package madacode.tool;

import madacode.tool.blob.FilesystemBlobStore;
import madacode.tool.blob.McpBlobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpBlobStoreTest {

    @Test
    void pngExtension(@TempDir Path dir) throws Exception {
        FilesystemBlobStore store = new FilesystemBlobStore(dir);
        McpBlobStore.Persisted p = store.persist(new byte[]{1, 2, 3}, "image/png");
        assertTrue(p.path().toString().endsWith(".png"));
    }

    @Test
    void pdfExtension(@TempDir Path dir) throws Exception {
        FilesystemBlobStore store = new FilesystemBlobStore(dir);
        McpBlobStore.Persisted p = store.persist(new byte[]{1, 2, 3}, "application/pdf");
        assertTrue(p.path().toString().endsWith(".pdf"));
    }

    @Test
    void nullMimeExtension(@TempDir Path dir) throws Exception {
        FilesystemBlobStore store = new FilesystemBlobStore(dir);
        McpBlobStore.Persisted p = store.persist(new byte[]{1, 2, 3}, null);
        assertTrue(p.path().toString().endsWith(".bin"));
    }

    @Test
    void unknownMimeExtension(@TempDir Path dir) throws Exception {
        FilesystemBlobStore store = new FilesystemBlobStore(dir);
        McpBlobStore.Persisted p = store.persist(new byte[]{1, 2, 3}, "application/octet-stream");
        assertTrue(p.path().toString().endsWith(".bin"));
    }

    @Test
    void contentAddressedSameBytesReturnsSamePath(@TempDir Path dir) throws Exception {
        FilesystemBlobStore store = new FilesystemBlobStore(dir);
        byte[] data = "hello world".getBytes();
        McpBlobStore.Persisted p1 = store.persist(data, "text/plain");
        McpBlobStore.Persisted p2 = store.persist(data, "text/plain");
        assertEquals(p1.path(), p2.path());
    }

    @Test
    void differentBytesProduceDifferentPaths(@TempDir Path dir) throws Exception {
        FilesystemBlobStore store = new FilesystemBlobStore(dir);
        McpBlobStore.Persisted p1 = store.persist("hello".getBytes(), "text/plain");
        McpBlobStore.Persisted p2 = store.persist("world".getBytes(), "text/plain");
        assertNotEquals(p1.path(), p2.path());
    }

    @Test
    void fileActuallyWritten(@TempDir Path dir) throws Exception {
        FilesystemBlobStore store = new FilesystemBlobStore(dir);
        byte[] data = "test content".getBytes();
        McpBlobStore.Persisted p = store.persist(data, "text/plain");
        assertTrue(Files.exists(p.path()));
        byte[] read = Files.readAllBytes(p.path());
        assertEquals(data.length, read.length);
        for (int i = 0; i < data.length; i++) {
            assertEquals(data[i], read[i]);
        }
    }

    @Test
    void persistedBytesMatchDataLength(@TempDir Path dir) throws Exception {
        FilesystemBlobStore store = new FilesystemBlobStore(dir);
        byte[] data = new byte[42];
        McpBlobStore.Persisted p = store.persist(data, "image/png");
        assertEquals(42, p.bytes());
    }
}
