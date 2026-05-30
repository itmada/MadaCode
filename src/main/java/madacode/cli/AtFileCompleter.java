package madacode.cli;

import madacode.core.session.ConversationSession;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class AtFileCompleter {

    public static final long MAX_FILE_BYTES = 128 * 1024;
    public static final int DEFAULT_LIMIT = 8;

    private static final Pattern MENTION = Pattern.compile("(?<!\\S)@([A-Za-z0-9_./-]+)");

    private AtFileCompleter() {}

    public static String expandMentions(String input, ConversationSession session) {
        if (input == null || input.indexOf('@') < 0) {
            return input;
        }
        Matcher matcher = MENTION.matcher(input);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String raw = normalizeMentionPath(matcher.group(1));
            Optional<Path> safePath = safeRegularFile(session.workingDirectory(), raw);
            if (safePath.isEmpty()) {
                continue;
            }
            try {
                if (Files.size(safePath.get()) > MAX_FILE_BYTES) {
                    continue;
                }
                String content = Files.readString(safePath.get());
                String replacement = "<file path=\"" + raw + "\">\n" + content + "\n</file>";
                matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            } catch (IOException ignored) {
                // Leave unreadable mentions untouched.
            }
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static Optional<SuggestionSet> suggestionSet(
            String input, int cursor, Path workingDirectory, int limit) {
        Mention mention = mentionAtCursor(input, cursor);
        if (mention == null) {
            return Optional.empty();
        }
        List<Suggestion> suggestions = suggestions(workingDirectory, mention.prefix(), limit);
        if (suggestions.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new SuggestionSet(mention.start(), mention.end(), suggestions));
    }

    public static List<Suggestion> suggestions(Path workingDirectory, String prefix, int limit) {
        String normalizedPrefix = normalizeMentionPath(prefix);
        Path cwd = workingDirectory.toAbsolutePath().normalize();
        Path parent = parentForPrefix(cwd, normalizedPrefix);
        String filePrefix = filePrefix(normalizedPrefix);
        if (!parent.startsWith(cwd) || !Files.isDirectory(parent) || containsGitSegment(cwd.relativize(parent))) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(parent)) {
            return stream
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                            || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().startsWith(filePrefix))
                    .filter(path -> safeCandidate(cwd, path))
                    .sorted(Comparator.comparing((Path p) -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                            .thenComparing(Path::toString))
                    .limit(Math.max(0, limit))
                    .map(path -> toSuggestion(cwd, path))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private static Suggestion toSuggestion(Path cwd, Path path) {
        Path rel = cwd.relativize(path.toAbsolutePath().normalize());
        boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        String value = rel.toString().replace('\\', '/') + (directory ? "/" : "");
        return new Suggestion(value, directory);
    }

    private static boolean safeCandidate(Path cwd, Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(cwd) || containsGitSegment(cwd.relativize(normalized))) {
            return false;
        }
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static Optional<Path> safeRegularFile(Path workingDirectory, String raw) {
        Path cwd = workingDirectory.toAbsolutePath().normalize();
        Path path = cwd.resolve(raw).normalize();
        if (!path.startsWith(cwd) || containsGitSegment(cwd.relativize(path))) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(path);
    }

    private static Mention mentionAtCursor(String input, int cursor) {
        int safeCursor = Math.max(0, Math.min(cursor, input.length()));
        int start = safeCursor;
        while (start > 0 && !Character.isWhitespace(input.charAt(start - 1))) {
            start--;
        }
        if (start >= safeCursor || input.charAt(start) != '@') {
            return null;
        }
        return new Mention(start, safeCursor, input.substring(start + 1, safeCursor));
    }

    private static Path parentForPrefix(Path cwd, String prefix) {
        int slash = prefix.lastIndexOf('/');
        if (slash < 0) {
            return cwd;
        }
        return cwd.resolve(prefix.substring(0, slash)).normalize();
    }

    private static String filePrefix(String prefix) {
        int slash = prefix.lastIndexOf('/');
        return slash < 0 ? prefix : prefix.substring(slash + 1);
    }

    private static String normalizeMentionPath(String raw) {
        String value = raw == null ? "" : raw.strip();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value;
    }

    private static boolean containsGitSegment(Path relative) {
        for (Path part : relative) {
            if (".git".equals(part.toString())) {
                return true;
            }
        }
        return false;
    }

    private record Mention(int start, int end, String prefix) {}

    public record Suggestion(String relativePath, boolean directory) {}

    public record SuggestionSet(int start, int end, List<Suggestion> suggestions) {
        public SuggestionSet {
            suggestions = List.copyOf(suggestions);
        }
    }
}
