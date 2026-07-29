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
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Attempt-scoped trace boundary. It aggregates completed control, worker, and
 * <em>sub-agent</em> sessions and derives authoritative file effects from workspace
 * snapshots.
 *
 * <p>Control and worker sessions are recorded explicitly by the launchers. Sub-agent
 * sessions are spawned dynamically inside tool execution and are not reachable by a
 * parent-session scan, so they are instead registered via {@link #trackSubAgent} at
 * spawn time (wired through {@code ConversationSession#registerSubAgent}) and scanned
 * at {@link #finish}. Without this, tool/decoy/read-before-edit checks would silently
 * miss everything a sub-agent did — and read-before-edit would even raise a false
 * violation, seeing a file modified with no corresponding edit invocation.
 */
public final class ExecutionTraceCollector {

    private final Path workspace;
    private final Map<String, String> initialFiles;
    private final List<ToolInvocation> invocations = new ArrayList<>();
    private final List<String> userTurns = new ArrayList<>();
    private final List<String> assistantTurns = new ArrayList<>();
    private final Map<ConversationSession, Integer> sessionCursors = new IdentityHashMap<>();
    // Sub-agent sessions captured at spawn time; their messages are still empty then,
    // so we hold the references and scan them at finish() once the tree has quiesced.
    private final Set<ConversationSession> subAgentSessions =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private int nextOrdinal;

    public ExecutionTraceCollector(Path workspace) {
        this.workspace = workspace.toAbsolutePath().normalize();
        this.initialFiles = snapshot(this.workspace);
    }

    /**
     * Registers a sub-agent session spawned during this attempt. Safe to call from the
     * tool-worker threads that spawn sub-agents (the collector is synchronized). The
     * session is scanned at {@link #finish}, by which point its transcript is complete.
     */
    public synchronized void trackSubAgent(ConversationSession session) {
        if (session != null) {
            subAgentSessions.add(session);
        }
    }

    public synchronized void recordSession(
            ConversationSession session,
            ToolInvocation.Phase phase) {
        if (session == null) {
            return;
        }
        int cursor = sessionCursors.getOrDefault(session, 0);
        List<Message> messages = session.transcriptMessages();
        if (cursor >= messages.size()) {
            return;
        }
        Map<String, String> results = toolResults(session.transcriptMessages());
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
                            session.toolAccessEvidence(toolUse.id()),
                            phase,
                            nextOrdinal++));
                }
            }
        }
        sessionCursors.put(session, messages.size());
    }

    public synchronized ExecutionTrace finish(String finalText, RunMetrics metrics) {
        // Scan sub-agent sessions last: their transcripts are complete by now, and
        // recordSession is idempotent (cursor-guarded), so re-scanning is harmless.
        for (ConversationSession subAgent : subAgentSessions) {
            recordSession(subAgent, ToolInvocation.Phase.SUBAGENT);
        }
        // invocations now span control + worker + sub-agent, making its size the
        // authoritative tool-call total for the whole tree (the per-session count in
        // RunMetrics misses sub-agent calls).
        RunMetrics reconciledMetrics = metrics == null
                ? null
                : metrics.withToolCalls(invocations.size());
        return new ExecutionTrace(
                invocations,
                diff(initialFiles, snapshot(workspace)),
                userTurns,
                assistantTurns,
                finalText,
                reconciledMetrics);
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
