package madacode.governance;

/**
 * Default approval stance for one execution context. Semantic rules may still
 * deny unsafe inputs before this posture is consulted.
 */
public record ApprovalPosture(
        Stance read,
        Stance edit,
        Stance exec,
        Stance network,
        Stance state) {

    public enum Stance {
        ALLOW_SILENT,
        PROMPT,
        DENY
    }

    public static ApprovalPosture defaultInteractive() {
        return new ApprovalPosture(
                Stance.ALLOW_SILENT,
                Stance.PROMPT,
                Stance.PROMPT,
                Stance.PROMPT,
                Stance.ALLOW_SILENT);
    }

    public static ApprovalPosture editInteractive() {
        return new ApprovalPosture(
                Stance.ALLOW_SILENT,
                Stance.ALLOW_SILENT,
                Stance.PROMPT,
                Stance.PROMPT,
                Stance.ALLOW_SILENT);
    }

    public static ApprovalPosture bypassInteractive() {
        return new ApprovalPosture(
                Stance.ALLOW_SILENT,
                Stance.ALLOW_SILENT,
                Stance.ALLOW_SILENT,
                Stance.ALLOW_SILENT,
                Stance.ALLOW_SILENT);
    }

    public static ApprovalPosture longRunningWorker() {
        return new ApprovalPosture(
                Stance.ALLOW_SILENT,
                Stance.ALLOW_SILENT,
                Stance.ALLOW_SILENT,
                Stance.ALLOW_SILENT,
                Stance.ALLOW_SILENT);
    }
}
