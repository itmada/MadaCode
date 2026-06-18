package madacode.eval;

import madacode.core.model.ContentBlock;
import madacode.core.model.Message;
import madacode.core.model.MessageRole;
import madacode.core.session.ConversationSession;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Attempt-scoped trace boundary. It aggregates completed control/worker/subagent sessions
 * and derives authoritative file effects from workspace snapshots.
 */
public final class ExecutionTraceCollector {

    private final Path workspace;
    private final Map<String, String> initialFiles;
    private final List<ToolInvocation> invocations = new ArrayList<>();
    private final List<String> userTurns = new ArrayList<>();
    private final List<String> assistantTurns = new ArrayList<>();
    private final Map<ConversationSession, Integer> sessionCursors = new IdentityHashMap<>();
    private int nextOrdinal;

    public ExecutionTraceCollector(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.initialFiles = snapshot(this.workspace);
    }

    public synchronized void recordSession(
            ConversationSession session,
            ToolInvocation.Phase phase) {
        if (session == null) {
            return;
        }
        int cursor = sessionCursors.getOrDefault(session, 0);
        List<Message> messages = session.messages();
        if (cursor >= messages.size()) {
            return;
        }
        Map<String, String> results = toolResults(session.messages());
        for (Message message : messages.subList(cursor, messages.size())) {
            if (phase == ToolInvocation.Phase.CONTROL
                    && message.role() == MessageRole.USER
                    && !message.isControllerEvent()) {
                addNonBlank(userTurns, message.content());
            }
            if (message.role() == MessageRole.ASSISTANT) {
                addNonBlank(assistantTurns, message.content());
            }
            for (ContentBlock block : message.contentBlocks()) {
                if (block instanceof ContentBlock.ToolUseBlock toolUse) {
                    invocations.add(new ToolInvocation(
                            toolUse.name(),
                            toolUse.input().toString(),
                            results.getOrDefault(toolUse.id(), ""),
                            phase,
                            nextOrdinal++));
                }
            }
        }
        sessionCursors.put(session, messages.size());
    }

    public synchronized ExecutionTrace finish(String finalText, RunMetrics metrics) {
        return new ExecutionTrace(
                invocations,
                diff(initialFiles, snapshot(workspace)),
                userTurns,
                assistantTurns,
                finalText,
                metrics);
    }

    private static Map<String, String> toolResults(List<Message> messages) {
        Map<String, String> results = new HashMap<>();
        for (Message message : messages) {
            for (ContentBlock block : message.contentBlocks()) {
                if (block instanceof ContentBlock.ToolResultBlock result) {
                    results.put(result.toolUseId(), result.content());
                }
            }
        }
        return results;
    }

    private static void addNonBlank(List<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value);
        }
    }

    private static List<TouchedFile> diff(
            Map<String, String> before,
            Map<String, String> after) {
        java.util.SortedSet<String> paths = new java.util.TreeSet<>();
        paths.addAll(before.keySet());
        paths.addAll(after.keySet());
        List<TouchedFile> effects = new ArrayList<>();
        for (String path : paths) {
            String oldHash = before.get(path);
            String newHash = after.get(path);
            if (oldHash == null) {
                effects.add(new TouchedFile(path, TouchedFile.ChangeKind.CREATED));
            } else if (newHash == null) {
                effects.add(new TouchedFile(path, TouchedFile.ChangeKind.DELETED));
            } else if (!oldHash.equals(newHash)) {
                effects.add(new TouchedFile(path, TouchedFile.ChangeKind.MODIFIED));
            }
        }
        return List.copyOf(effects);
    }

    private static Map<String, String> snapshot(Path root) {
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> !isHarnessManaged(root, path))
                    .sorted(Comparator.comparing(value -> root.relativize(value).toString()))
                    .toList()) {
                files.put(
                        root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/"),
                        sha256(Files.readAllBytes(path)));
            }
            return Map.copyOf(files);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to snapshot eval workspace " + root, e);
        }
    }

    private static boolean isHarnessManaged(Path root, Path path) {
        String relative = root.relativize(path).toString()
                .replace(path.getFileSystem().getSeparator(), "/");
        return relative.equals(".mada/long-running")
                || relative.startsWith(".mada/long-running/");
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
