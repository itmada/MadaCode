package madacode.eval;

import com.fasterxml.jackson.annotation.JsonProperty;
import madacode.permission.PermissionMode;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One eval case: a self-contained "exam question" for the agent.
 *
 * <p>A case is pure data — it declares <em>which workflow mode</em> to run under,
 * <em>which permission policy</em> applies, the natural-language instruction handed
 * to the agent, and the capability tags used only for report grouping. The accompanying
 * {@code workspace/} directory provides the starting files and {@code verify.sh} is the
 * objective pass/fail check (see {@link Scorer}).
 *
 * <p>Capabilities are expressed as fields/tags on a single uniform schema rather than a
 * bespoke interface per capability — this is what keeps one generic {@link EvalRunner}
 * able to drive every kind of evaluation.
 */
public record EvalCase(
        String id,
        String description,
        String mode,
        @JsonProperty("permissionMode") String permissionModeId,
        List<String> capabilities,
        String instruction,
        boolean planMode,
        Integer samples,
        Integer maxIterations,
        Integer maxCycles,
        Integer workerMaxIterations,
        Integer timeoutSeconds,
        Integer verifyTimeoutSeconds,
        Integer maxProcessOutputBytes,
        String expectedVerdict,
        EvalChecks checks,
        List<ConversationTurn> conversation,
        String repository,
        String baseCommit) {

    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public EvalCase {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("case id must not be blank");
        }
        id = id.strip();
        if (!SAFE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("case id contains unsupported characters: " + id);
        }
        conversation = conversation == null ? List.of() : List.copyOf(conversation);
        if ((instruction == null || instruction.isBlank()) && conversation.isEmpty()) {
            throw new IllegalArgumentException(
                    "case " + id + ": instruction or conversation must be declared");
        }
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("case " + id + ": mode must not be blank");
        }
        if (permissionModeId == null || permissionModeId.isBlank()) {
            throw new IllegalArgumentException("case " + id + ": permissionMode must not be blank");
        }
        mode = mode.strip().toLowerCase(Locale.ROOT);
        instruction = instruction == null || instruction.isBlank()
                ? conversation.getFirst().text()
                : instruction.strip();
        if (!conversation.isEmpty()
                && !instruction.equals(conversation.getFirst().text())) {
            throw new IllegalArgumentException(
                    "case " + id
                            + ": instruction must equal the first conversation turn when both are declared");
        }
        if (!conversation.isEmpty()
                && conversation.getFirst().trigger() != ConversationTurn.Trigger.ALWAYS) {
            throw new IllegalArgumentException(
                    "case " + id + ": first conversation turn must use trigger ALWAYS");
        }
        checks = checks == null ? EvalChecks.NONE : checks;
        capabilities = capabilities == null
                ? List.of()
                : capabilities.stream()
                        .map(value -> value == null ? "" : value.strip())
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .toList();
        expectedVerdict = expectedVerdict == null ? null : expectedVerdict.strip().toUpperCase(Locale.ROOT);
        if (expectedVerdict != null && !expectedVerdict.equals("PASS") && !expectedVerdict.equals("FAIL")) {
            throw new IllegalArgumentException(
                    "case " + id + ": expectedVerdict must be PASS or FAIL when present");
        }
        requirePositive(id, "samples", samples);
        requirePositive(id, "maxIterations", maxIterations);
        requirePositive(id, "maxCycles", maxCycles);
        requirePositive(id, "workerMaxIterations", workerMaxIterations);
        requirePositive(id, "timeoutSeconds", timeoutSeconds);
        requirePositive(id, "verifyTimeoutSeconds", verifyTimeoutSeconds);
        requirePositive(id, "maxProcessOutputBytes", maxProcessOutputBytes);
        repository = repository == null || repository.isBlank() ? null : repository.strip();
        baseCommit = baseCommit == null || baseCommit.isBlank() ? null : baseCommit.strip();
        if ((repository == null) != (baseCommit == null)) {
            throw new IllegalArgumentException(
                    "case " + id + ": repository and baseCommit must be declared together");
        }
        if (repository != null && !repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException(
                    "case " + id + ": repository must use the owner/name form");
        }
        if (baseCommit != null && !baseCommit.matches("[0-9a-fA-F]{7,64}")) {
            throw new IllegalArgumentException(
                    "case " + id + ": baseCommit must be a Git commit hash");
        }
    }

    /** Backward-compatible constructor for ordinary cases without a pinned Git baseline. */
    public EvalCase(
            String id,
            String description,
            String mode,
            String permissionModeId,
            List<String> capabilities,
            String instruction,
            boolean planMode,
            Integer samples,
            Integer maxIterations,
            Integer maxCycles,
            Integer workerMaxIterations,
            Integer timeoutSeconds,
            Integer verifyTimeoutSeconds,
            Integer maxProcessOutputBytes,
            String expectedVerdict,
            EvalChecks checks,
            List<ConversationTurn> conversation) {
        this(
                id,
                description,
                mode,
                permissionModeId,
                capabilities,
                instruction,
                planMode,
                samples,
                maxIterations,
                maxCycles,
                workerMaxIterations,
                timeoutSeconds,
                verifyTimeoutSeconds,
                maxProcessOutputBytes,
                expectedVerdict,
                checks,
                conversation,
                null,
                null);
    }

    /** Whether this case must be executed from its declared repository baseline. */
    public boolean hasGitBaseline() {
        return repository != null && baseCommit != null;
    }

    /** Scripted user turns, with legacy instruction-only cases represented as one turn. */
    public List<ConversationTurn> effectiveConversation() {
        return conversation.isEmpty()
                ? List.of(new ConversationTurn(instruction, ConversationTurn.Trigger.ALWAYS))
                : conversation;
    }

    /**
     * Number of independent attempts to run for this case. A real model is non-deterministic,
     * so a single attempt is a high-variance binary; sampling lets the report express a
     * pass rate (k/N) and pass@k. Defaults to 3.
     */
    public int samplesOrDefault() {
        return samples == null ? 3 : samples;
    }

    /** Effective per-turn iteration guard for single-turn modes (common). */
    public int maxIterationsOrDefault() {
        return maxIterations == null ? 30 : maxIterations;
    }

    /** Effective worker-cycle guard for long-running mode. */
    public int maxCyclesOrDefault() {
        return maxCycles == null ? 12 : maxCycles;
    }

    public int workerMaxIterationsOrDefault() {
        return workerMaxIterations == null ? 30 : workerMaxIterations;
    }

    public int timeoutSecondsOrDefault() {
        return timeoutSeconds == null ? 30 * 60 : timeoutSeconds;
    }

    public int verifyTimeoutSecondsOrDefault() {
        return verifyTimeoutSeconds == null ? 5 * 60 : verifyTimeoutSeconds;
    }

    public int maxProcessOutputBytesOrDefault() {
        return maxProcessOutputBytes == null ? 1024 * 1024 : maxProcessOutputBytes;
    }

    public PermissionMode permissionMode() {
        String n = permissionModeId.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        return PermissionMode.parse(n)
                .orElseThrow(() -> new IllegalArgumentException(
                        "case " + id + ": unknown permissionMode '" + permissionModeId + "'"));
    }

    private static void requirePositive(String id, String field, Integer value) {
        if (value != null && value <= 0) {
            throw new IllegalArgumentException("case " + id + ": " + field + " must be positive");
        }
    }
}
