package madacode.cli.slash;

import madacode.core.model.Message;
import madacode.core.session.LongRunningStage;
import madacode.core.session.SessionMode;
import madacode.permission.PermissionMode;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

final class ModeCommand implements SlashCommand {

    @Override public String name() { return "mode"; }
    @Override public String description() { return "Show or switch the active workflow mode"; }
    @Override public String usage() { return "/mode [common|long-running]"; }

    @Override
    public Optional<ArgumentProvider> argumentProvider(SlashContext ctx) {
        return Optional.of(partial -> {
            String needle = partial == null ? "" : partial.toLowerCase(Locale.ROOT);
            return Arrays.stream(SessionMode.values())
                    .filter(mode -> mode.id().contains(needle))
                    .map(mode -> new ArgumentProvider.Candidate(mode.id(), mode.description()))
                    .toList();
        });
    }

    @Override
    public SlashAction execute(SlashContext ctx, String args) {
        String requested = args.strip();
        if (requested.isBlank()) {
            if (ctx.modeChooser().isPresent()) {
                Optional<String> selected = ctx.modeChooser().get().chooseMode(
                        Arrays.stream(SessionMode.values())
                                .map(SessionMode::id)
                                .toList());
                if (selected.isEmpty()) {
                    SlashFeedback.muted(ctx.screen(), "Mode selection cancelled.");
                    return new SlashAction.Handled();
                }
                requested = selected.get();
            } else {
                listModes(ctx);
                return new SlashAction.Handled();
            }
        }

        Optional<SessionMode> parsed = SessionMode.parse(requested);
        if (parsed.isEmpty()) {
            ctx.screen().scrollback("Unknown mode: " + requested);
            listModes(ctx);
            return new SlashAction.Handled();
        }

        SessionMode mode = parsed.get();
        mode.applyTo(ctx.session());
        if (ctx.sessionContext() != null) {
            ctx.sessionContext().setWorkflowMode(mode);
        }

        if (mode == SessionMode.LONG_RUNNING) {
            ctx.session().setPlanMode(false);
            ctx.session().setLongRunningTaskId(null);
            ctx.session().setLongRunningTaskDirectory(null);
            ctx.session().setLongRunningTaskTitle(null);
            ctx.session().setLongRunningReason(null);
            ctx.session().setLongRunningPlanSummary(null);
            ctx.session().clearPendingLongRunningTransitionRequest();
            ctx.session().setPermissionMode(PermissionMode.BYPASS);
            ctx.session().setLongRunningStage(LongRunningStage.DRAFT);
            if (ctx.sessionContext() != null) {
                ctx.sessionContext().setPlanMode(false);
                ctx.sessionContext().setPermissionMode(PermissionMode.BYPASS);
            }
            SlashFeedback.muted(ctx.screen(), "Entered long-running mode.");
            SlashFeedback.muted(ctx.screen(),
                    "This mode is for larger serial relay tasks. It starts with planning and confirmation, and will not execute immediately.");
            SlashFeedback.muted(ctx.screen(),
                    "Current permission is all-pass. Use /permission to change it.");
            // Clear messages for a clean control session
            ctx.session().replaceMessages(java.util.List.of(Message.system(
                    "[long-running mode entered] This mode is for larger serial relay tasks. "
                            + "It starts in DRAFT with a fresh control session, uses all-pass permission by default, "
                            + "and creates the task shell when the user provides the task request.")));
            return new SlashAction.Handled(true);
        }

        SlashFeedback.muted(ctx.screen(), "Mode set to: " + mode.id());
        return new SlashAction.Handled(true);
    }

    private static void listModes(SlashContext ctx) {
        SessionMode current = SessionMode.from(ctx.session());
        ctx.screen().scrollback("Modes:");
        for (SessionMode mode : SessionMode.values()) {
            String marker = mode == current ? "*" : " ";
            ctx.screen().scrollback("  " + marker + " " + mode.id() + " - " + mode.description());
        }
    }
}
