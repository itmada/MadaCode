package madacode.events;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LogbackConfigTest {

    @Test
    void debugModeBranchesUseDebugRootLevel() throws Exception {
        String xml = Files.readString(Path.of("src/main/resources/logback.xml"));

        assertTrue(xml.contains("<root level=\"DEBUG\">"));
        assertTrue(xml.contains("property(\"MADA_DEBUG\").equalsIgnoreCase(\"true\")"));
        assertTrue(xml.contains("<appender-ref ref=\"SESSION_DEBUG\" />"));
    }
}
