package madacode.eval;

import java.util.List;

/** Explicit wire DTO for the case data a containerized attempt may see. */
public record EvalAttemptCaseJson(
        String id,
        String description,
        String mode,
        String permissionMode,
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
        ChecksJson checks,
        List<ConversationJson> conversation,
        String repository,
        String baseCommit) {

    public EvalAttemptCaseJson {
        capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
        conversation = conversation == null ? List.of() : List.copyOf(conversation);
        checks = checks == null ? ChecksJson.NONE : checks;
    }

    static EvalAttemptCaseJson from(EvalCase evalCase) {
        return new EvalAttemptCaseJson(
                evalCase.id(),
                evalCase.description(),
                evalCase.mode(),
                evalCase.permissionModeId(),
                evalCase.capabilities(),
                evalCase.instruction(),
                evalCase.planMode(),
                evalCase.samples(),
                evalCase.maxIterations(),
                evalCase.maxCycles(),
                evalCase.workerMaxIterations(),
                evalCase.timeoutSeconds(),
                evalCase.verifyTimeoutSeconds(),
                evalCase.maxProcessOutputBytes(),
                evalCase.expectedVerdict(),
                ChecksJson.from(evalCase.checks()),
                evalCase.conversation().stream().map(ConversationJson::from).toList(),
                evalCase.repository(),
                evalCase.baseCommit());
    }

    EvalCase toEvalCase() {
        return new EvalCase(
                id,
                description,
                mode,
                permissionMode,
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
                checks.toEvalChecks(),
                conversation.stream().map(ConversationJson::toConversationTurn).toList(),
                repository,
                baseCommit);
    }

    public record ChecksJson(
            TrajectoryJson trajectory,
            EfficiencyJson efficiency,
            DialogJson dialog,
            SafetyJson safety) {

        static final ChecksJson NONE = new ChecksJson(null, null, null, null);

        public ChecksJson {
            // Keep the wire shape closed to the four declared check groups.
        }

        static ChecksJson from(EvalChecks checks) {
            EvalChecks safe = checks == null ? EvalChecks.NONE : checks;
            return new ChecksJson(
                    TrajectoryJson.from(safe.trajectory()),
                    EfficiencyJson.from(safe.efficiency()),
                    DialogJson.from(safe.dialog()),
                    SafetyJson.from(safe.safety()));
        }

        EvalChecks toEvalChecks() {
            return new EvalChecks(
                    trajectory == null ? null : trajectory.toTrajectoryChecks(),
                    efficiency == null ? null : efficiency.toEfficiencyChecks(),
                    dialog == null ? null : dialog.toDialogChecks(),
                    safety == null ? null : safety.toSafetyChecks());
        }
    }

    public record ConversationJson(
            String text,
            ConversationTurn.Trigger trigger) {

        static ConversationJson from(ConversationTurn turn) {
            if (turn == null) {
                return null;
            }
            return new ConversationJson(turn.text(), turn.trigger());
        }

        ConversationTurn toConversationTurn() {
            return new ConversationTurn(text, trigger);
        }
    }

    public record TrajectoryJson(
            List<String> allowedTools,
            List<String> forbiddenTools,
            List<String> fileWhitelist,
            boolean requireReadBeforeEdit,
            Boolean gating) {

        public TrajectoryJson {
            allowedTools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
            forbiddenTools = forbiddenTools == null ? List.of() : List.copyOf(forbiddenTools);
            fileWhitelist = fileWhitelist == null ? List.of() : List.copyOf(fileWhitelist);
        }

        static TrajectoryJson from(TrajectoryChecks checks) {
            if (checks == null) {
                return null;
            }
            return new TrajectoryJson(
                    checks.allowedTools(),
                    checks.forbiddenTools(),
                    checks.fileWhitelist(),
                    checks.requireReadBeforeEdit(),
                    checks.gating());
        }

        TrajectoryChecks toTrajectoryChecks() {
            return new TrajectoryChecks(
                    allowedTools,
                    forbiddenTools,
                    fileWhitelist,
                    requireReadBeforeEdit,
                    gating);
        }
    }

    public record EfficiencyJson(
            Integer maxToolCalls,
            Integer maxTokens,
            Boolean gating) {

        static EfficiencyJson from(EfficiencyChecks checks) {
            if (checks == null) {
                return null;
            }
            return new EfficiencyJson(checks.maxToolCalls(), checks.maxTokens(), checks.gating());
        }

        EfficiencyChecks toEfficiencyChecks() {
            return new EfficiencyChecks(maxToolCalls, maxTokens, gating);
        }
    }

    public record DialogJson(
            Boolean expectClarifyingQuestion,
            String rubric,
            Boolean gating) {

        static DialogJson from(DialogChecks checks) {
            if (checks == null) {
                return null;
            }
            return new DialogJson(checks.expectClarifyingQuestion(), checks.rubric(), checks.gating());
        }

        DialogChecks toDialogChecks() {
            return new DialogChecks(expectClarifyingQuestion, rubric, gating);
        }
    }

    public record SafetyJson(
            Boolean mustRefuse,
            Boolean forbidExfiltration,
            List<String> decoyFiles,
            Boolean gating) {

        public SafetyJson {
            decoyFiles = decoyFiles == null ? List.of() : List.copyOf(decoyFiles);
        }

        static SafetyJson from(SafetyChecks checks) {
            if (checks == null) {
                return null;
            }
            return new SafetyJson(
                    checks.mustRefuse(),
                    checks.forbidExfiltration(),
                    checks.decoyFiles(),
                    checks.gating());
        }

        SafetyChecks toSafetyChecks() {
            return new SafetyChecks(mustRefuse, forbidExfiltration, decoyFiles, gating);
        }
    }
}
