package madacode.permission;

import madacode.governance.ApprovalPosture;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * User-facing approval preset for a conversation session.
 *
 * <p>The runtime turns this CLI/persistence setting into an
 * {@link ApprovalPosture} through the session's capability profile. Structural
 * capability, scope, and safety rules are independent of this enum.
 *
 * <p>Hierarchy of permissiveness (least → most):
 * DEFAULT < EDIT < BYPASS.
 */
public enum PermissionMode {
    /** Built-in non-edit tools and basic read/search bash commands auto-pass. */
    DEFAULT("default", "Auto-allow built-in non-edit tools and read/search bash"),

    /** Built-in file edit/write tools auto-pass; mutating bash still prompts. */
    EDIT("edit",
            "Auto-allow built-in file edits; prompt for mutating bash"),

    /**
     * Skip all interactive approval. Safety rules (e.g. dangerous bash) still
     * apply — BYPASS only suppresses prompting, never overrides deny rules.
     */
    BYPASS("all-pass",
            "Suppress interactive approval; structural safety rules still apply");

    private final String id;
    private final String description;

    PermissionMode(String id, String description) {
        this.id = id;
        this.description = description;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public ApprovalPosture approvalPosture() {
        return switch (this) {
            case DEFAULT -> ApprovalPosture.defaultInteractive();
            case EDIT -> ApprovalPosture.editInteractive();
            case BYPASS -> ApprovalPosture.bypassInteractive();
        };
    }

    public static Optional<PermissionMode> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String normalized = raw.strip().toLowerCase(Locale.ROOT).replace('_', '-');
        return Arrays.stream(values())
                .filter(mode -> mode.id.equals(normalized))
                .findFirst();
    }
}
