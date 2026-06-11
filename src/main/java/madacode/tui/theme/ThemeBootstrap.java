package madacode.tui.theme;

import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** One-shot startup wiring: capability detection + persisted preference. */
public final class ThemeBootstrap {

    private static final Path PREF_FILE =
            Path.of(System.getProperty("user.home"), ".mada", "theme");

    private ThemeBootstrap() {}

    public static void initialize(Terminal terminal) {
        boolean mono = System.getenv("NO_COLOR") != null
                && !System.getenv("NO_COLOR").isEmpty();
        Integer maxColors = terminal.getNumericCapability(InfoCmp.Capability.max_colors);
        // Unknown capabilities keep the current 256-color behavior; only explicit
        // low-color terminals downgrade, and NO_COLOR can force monochrome.
        boolean basic = maxColors != null && maxColors > 0 && maxColors < 256;
        Themes.configureCapabilities(basic, mono);
        Themes.setActive(readPreference());
    }

    private static String readPreference() {
        try {
            if (Files.isRegularFile(PREF_FILE)) {
                String name = Files.readString(PREF_FILE).strip();
                if (Themes.names().contains(name)) return name;
            }
        } catch (IOException ignored) {
        }
        return "dark";
    }

    /** Best-effort persistence: failures must never break theme switching. */
    public static void savePreference(String name) {
        try {
            Files.createDirectories(PREF_FILE.getParent());
            Files.writeString(PREF_FILE, name + System.lineSeparator());
        } catch (IOException ignored) {
        }
    }
}
