package madacode.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ToolNameNormalizerTest {

    @Test
    void lowercasesViaLocaleRoot() {
        assertEquals("file_read", ToolNameNormalizer.normalize("FileRead"));
        assertEquals("file_read", ToolNameNormalizer.normalize("FILE_READ"));
    }

    @Test
    void replacesHyphensWithUnderscores() {
        assertEquals("web_fetch", ToolNameNormalizer.normalize("web-fetch"));
        assertEquals("a_b_c", ToolNameNormalizer.normalize("a-b-c"));
    }

    @Test
    void combinedHyphenAndCase() {
        assertEquals("file_read", ToolNameNormalizer.normalize("File-Read"));
    }

    @Test
    void normalizesList() {
        List<String> result = ToolNameNormalizer.normalize(List.of("FileRead", "GLOB", "web-fetch"));
        assertEquals(List.of("file_read", "glob", "web_fetch"), result);
    }

    @Test
    void nullStringYieldsNull() {
        assertNull(ToolNameNormalizer.normalize((String) null));
    }

    @Test
    void nullListYieldsEmptyList() {
        assertEquals(List.of(), ToolNameNormalizer.normalize((List<String>) null));
    }
}
