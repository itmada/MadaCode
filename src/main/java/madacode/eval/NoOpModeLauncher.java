package madacode.eval;

import madacode.core.session.ConversationSession;

/**
 * A launcher that does nothing — used only by {@code --self-test} to validate the sandbox
 * and scoring plumbing with zero model calls. It leaves the workspace untouched so the
 * case's verify.sh judges the initial files: a "must-pass" self-test case ships a correct
 * workspace, a "must-fail" one ships a broken workspace.
 */
public final class NoOpModeLauncher implements ModeLauncher {

    private final String modeId;

    public NoOpModeLauncher(String modeId) {
        this.modeId = modeId;
    }

    @Override
    public String modeId() {
        return modeId;
    }

    @Override
    public LaunchOutcome launch(EvalCase evalCase, ConversationSession session, EvalRunContext context) {
        return new LaunchOutcome(
                EvalResult.ExecutionStatus.COMPLETED,
                RunMetrics.ZERO,
                "noop",
                "");
    }
}
