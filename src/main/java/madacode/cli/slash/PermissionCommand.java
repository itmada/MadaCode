package madacode.cli.slash;

import madacode.permission.PermissionMode;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

final class PermissionCommand implements SlashCommand {

    @Override public String name() { return "permission"; }
    @Override public String description() { return "Show or switch the active permission mode"; }
    @Override public String usage() { return "/permission [strict|normal|all-pass]"; }

    @Override
    public Optional<ArgumentProvider> argumentProvider(SlashContext ctx) {
        return Optional.of(partial -> {
            String needle = partial == null ? "" : partial.toLowerCase(Locale.ROOT);
            return Arrays.stream(PermissionMode.values())
                    .filter(PermissionMode::isUserSelectable)
                    .filter(mode -> mode.id().contains(needle))
                    .map(mode -> new ArgumentProvider.Candidate(mode.id(), mode.description()))
                    .toList();
        });
    }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        String requested = args.strip();
        if (requested.isBlank()) {
            if (ctx.permissionChooser().isPresent()) {
                Optional<String> selected = ctx.permissionChooser().get().choosePermission(
                        SlashChoiceModels.choice(
                                "Permission",
                                "Active permission policy",
                                Arrays.stream(PermissionMode.values())
                                        .filter(PermissionMode::isUserSelectable)
                                        .map(PermissionMode::id)
                                        .toList(),
                                ctx.session().permissionMode().id()));
                if (selected.isEmpty()) {
                    SlashFeedback.muted(ctx.screen(), "Permission selection cancelled.");
                    return new SlashAction.Handled();
                }
                requested = selected.get();
            } else {
                listPermissions(ctx);
                return new SlashAction.Handled();
            }
        }

        Optional<PermissionMode> parsed = PermissionMode.parse(requested);
        // LONG_RUNNING_WORKSPACE is a worker-agent-only sandbox set automatically
        // by the runtime; reject it (and anything unknown) from manual selection.
        if (parsed.isEmpty() || !parsed.get().isUserSelectable()) {
            ctx.screen().scrollback("Unknown permission mode: " + requested);
            listPermissions(ctx);
            return new SlashAction.Handled();
        }

        PermissionMode permission = parsed.get();
        ctx.session().setPermissionMode(permission);
        if (ctx.sessionContext() != null) {
            ctx.sessionContext().setPermissionMode(permission);
        }

        SlashFeedback.muted(ctx.screen(), "Permission set to: " + permission.id());
        if (permission == PermissionMode.BYPASS) {
            SlashFeedback.muted(ctx.screen(),
                    "Warning: all-pass suppresses interactive approval. Structural safety rules still apply.");
        }
        return new SlashAction.Handled(true);
    }

    private static void listPermissions(SlashContext ctx) {
        PermissionMode current = ctx.session().permissionMode();
        ctx.screen().scrollback("Permissions:");
        for (PermissionMode mode : PermissionMode.values()) {
            if (!mode.isUserSelectable()) {
                continue;
            }
            String marker = mode == current ? "*" : " ";
            ctx.screen().scrollback("  " + marker + " " + mode.id() + " - " + mode.description());
        }
    }
}
