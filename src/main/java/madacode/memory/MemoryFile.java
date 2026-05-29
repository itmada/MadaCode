package madacode.memory;

import java.nio.file.Path;

public record MemoryFile(String name, String description, MemoryType type, String body, Path path) {

    public enum MemoryType {
        USER, FEEDBACK, PROJECT, REFERENCE;

        public static MemoryType parse(String s) {
            if (s == null) return null;
            try {
                return valueOf(s.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        public String slug() {
            return name().toLowerCase();
        }
    }
}
