package madacode.cli;

import madacode.cli.slash.SlashAction;
import madacode.cli.slash.SlashCommandRegistry;
import madacode.core.session.ConversationSession;
import madacode.core.model.Message;
import madacode.core.model.MessageRole;
import madacode.core.model.MetaEvent;
import madacode.core.session.SessionMode;
import madacode.provider.Model;
import madacode.provider.Provider;
import madacode.provider.ProviderRegistry;
import madacode.core.session.SessionStorage;
import madacode.core.model.TokenUsage;
import madacode.permission.PermissionMode;
import madacode.services.compact.CompactBudget;
import madacode.services.compact.CompactPlanner;
import madacode.services.compact.TokenEstimator;
import madacode.skill.Skill;
import madacode.skill.SkillLoader;
import madacode.skill.SkillRegistry;
import madacode.skill.SkillSource;
import madacode.skill.SkillStateStore;
import madacode.tui.TextScreen;
import madacode.tui.widget.SessionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SlashCommandHandlerTest {

    @TempDir
    Path tempDir;

    private SessionStorage storage;
    private ByteArrayOutputStream outBytes;
    private PrintStream out;
    private SlashCommandHandler handler;
    private ConversationSession current;

    @BeforeEach
    void setUp() {
        storage = new SessionStorage(tempDir);
        outBytes = new ByteArrayOutputStream();
        out = new PrintStream(outBytes);
        ProviderRegistry registry = createTestRegistry();
        handler = SlashCommandHandler.builder(storage, new TextScreen(out))
                .registry(SlashCommandRegistry.create(null))
                .providerRegistry(registry)
                .build();
        current = newSession("current", "init message");
    }

    private static ProviderRegistry createTestRegistry() {
        return ProviderRegistry.singleProvider(
                new Provider("test", "test-token",
                        URI.create("https://api.anthropic.com"),
                        "claude-opus-4-7",
                        List.of(
                                new Model("claude-opus-4-7", 200_000),
                                new Model("claude-sonnet-4-6", 200_000),
                                new Model("claude-haiku-4-5", 200_000))));
    }

    @AfterEach
    void tearDown() {
        out.close();
    }

    @Test
    void nonSlashLineReturnsContinue() {
        var action = handler.handle("normal input", current);
        assertInstanceOf(SlashAction.Continue.class, action);
    }

    @Test
    void emptySlashWithSpacesReturnsHandled() {
        var action = handler.handle("/  ", current);
        assertInstanceOf(SlashAction.Handled.class, action);
    }

    @Test
    void helpCommand() {
        var action = handler.handle("/help", current);
        assertInstanceOf(SlashAction.Handled.class, action);
        String output = outBytes.toString();
        assertTrue(output.startsWith(System.lineSeparator()), "expected leading blank line: " + output);
        assertTrue(output.contains("/sessions"));
        assertTrue(output.contains("/resume"));
        assertTrue(output.contains("/new"));
        assertTrue(output.contains("/delete"));
        assertTrue(output.contains("/permission"));
    }

    @Test
    void skillsCommandOutputIsOneSeparatedBlock() {
        SkillRegistry skillRegistry = new SkillRegistry(
                new SkillStateStore(tempDir.resolve("skills.json")),
                testSkillLoader());
        skillRegistry.reload();
        handler = SlashCommandHandler.builder(storage, new TextScreen(out))
                .providerRegistry(createTestRegistry())
                .registry(SlashCommandRegistry.create(skillRegistry))
                .build();

        var action = handler.handle("/skills", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        assertEquals(System.lineSeparator()
                        + "Skills:" + System.lineSeparator()
                        + "  [B] alpha                Alpha skill" + System.lineSeparator()
                        + "  [P] beta                 Beta skill" + System.lineSeparator(),
                outBytes.toString());
    }

    @Test
    void commandsOnlyMatchByPrimaryName() {
        // Aliases have been removed — only primary command names work.
        var h = handler.handle("/h", current);
        assertInstanceOf(SlashAction.Handled.class, h);
        assertTrue(outBytes.toString().contains("Unknown command"));

        var quit = handler.handle("/quit", current);
        assertInstanceOf(SlashAction.Handled.class, quit);
        assertTrue(outBytes.toString().contains("Unknown command"));
    }

    @Test
    void newCommandSavesCurrentAndSwitches() {
        storage.save(current); // pre-populate

        var action = handler.handle("/new", current);

        assertInstanceOf(SlashAction.SwitchSession.class, action);
        var ss = (SlashAction.SwitchSession) action;
        assertTrue(!ss.session().sessionId().equals("current"),
                "Should create a new session, not return current");
        assertTrue(ss.fresh(), "new command should mark the switched session as fresh");
        String output = outBytes.toString();
        assertTrue(output.contains("saved current session"));
        assertTrue(!output.contains("New session:"));
    }

    @Test
    void resumeSwitchesToExistingSession() {
        ConversationSession other = newSession("target-session", "hello target");
        storage.save(current);
        storage.save(other);

        var action = handler.handle("/resume target-session", current);

        assertInstanceOf(SlashAction.SwitchSession.class, action);
        var ss = (SlashAction.SwitchSession) action;
        assertEquals("target-session", ss.session().sessionId());
        assertEquals("", outBytes.toString());
    }

    @Test
    void resumeWithPrefixMatch() {
        ConversationSession other = newSession("abc123-def456", "unique prefix");
        storage.save(current);
        storage.save(other);

        var action = handler.handle("/resume abc123", current);

        assertInstanceOf(SlashAction.SwitchSession.class, action);
        var ss = (SlashAction.SwitchSession) action;
        assertEquals("abc123-def456", ss.session().sessionId());
    }

    @Test
    void resumeWithoutIdUsesChooserWhenAvailable() {
        ConversationSession other = newSession("chosen-session", "chosen");
        storage.save(current);
        storage.save(other);
        handler = SlashCommandHandler.builder(storage, new TextScreen(out))
                .providerRegistry(createTestRegistry())
                .sessionChooser((sessions, currentSessionId) -> Optional.of("chosen-session"))
                .registry(SlashCommandRegistry.create(null))
                .build();

        var action = handler.handle("/resume", current);

        assertInstanceOf(SlashAction.SwitchSession.class, action);
        var ss = (SlashAction.SwitchSession) action;
        assertEquals("chosen-session", ss.session().sessionId());
    }

    @Test
    void resumeWithoutIdCancelsWhenChooserCancels() {
        storage.save(current);
        handler = SlashCommandHandler.builder(storage, new TextScreen(out))
                .providerRegistry(createTestRegistry())
                .sessionChooser((sessions, currentSessionId) -> Optional.empty())
                .registry(SlashCommandRegistry.create(null))
                .build();

        var action = handler.handle("/resume", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        assertTrue(outBytes.toString().contains("Resume cancelled"));
    }

    @Test
    void resumeNonExistentPrintsError() {
        storage.save(current);

        var action = handler.handle("/resume nonexistent", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        assertTrue(outBytes.toString().contains("No session found"));
    }

    @Test
    void resumeInvalidSessionIdPrintsError() {
        storage.save(current);

        var action = handler.handle("/resume ../escape", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        assertTrue(outBytes.toString().contains("No session found"));
    }

    @Test
    void resumeSameSessionDoesNothing() {
        storage.save(current);

        var action = handler.handle("/resume current", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        assertTrue(outBytes.toString().contains("Already in that session"));
    }

    @Test
    void deleteRemovesOtherSession() {
        ConversationSession other = newSession("to-delete", "bye");
        storage.save(current);
        storage.save(other);

        var action = handler.handle("/delete to-delete", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        assertTrue(outBytes.toString().contains("Deleted"));
        assertTrue(storage.loadIfExists("to-delete").isEmpty());
    }

    @Test
    void deleteCurrentSessionRefused() {
        storage.save(current);

        var action = handler.handle("/delete current", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        assertTrue(outBytes.toString().contains("Cannot delete the current session"));
        assertTrue(storage.loadIfExists("current").isPresent());
    }

    @Test
    void exitCommand() {
        var action = handler.handle("/exit", current);
        assertInstanceOf(SlashAction.Exit.class, action);
    }

    @Test
    void modelCommandListsModelsWithoutInteractiveChooser() {
        var action = handler.handle("/model", current);
        assertInstanceOf(SlashAction.Handled.class, action);
        assertTrue(outBytes.toString().contains("claude-opus-4-7"),
                "expected claude-opus-4-7 in output but got: " + outBytes.toString());
    }

    @Test
    void modelCommandSetOutputIsDimmedAndSeparatedFromNextPrompt() {
        var action = handler.handle("/model claude-sonnet-4-6", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        String output = outBytes.toString();
        assertTrue(output.startsWith(System.lineSeparator()), "expected leading blank line: " + output);
        assertTrue(stripAnsi(output).contains("Model set to: claude-sonnet-4-6"));
        assertTrue(output.contains("\u001B["), "expected styled output: " + output);
    }

    @Test
    void modelCommandCancelOutputIsDimmedAndSeparatedFromNextPrompt() {
        handler = SlashCommandHandler.builder(storage, new TextScreen(out))
                .providerRegistry(createTestRegistry())
                .modelChooser(models -> Optional.empty())
                .registry(SlashCommandRegistry.create(null))
                .build();

        var action = handler.handle("/model", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        String output = outBytes.toString();
        assertTrue(output.startsWith(System.lineSeparator()), "expected leading blank line: " + output);
        assertTrue(stripAnsi(output).contains("Model selection cancelled."));
        assertTrue(output.contains("\u001B["), "expected styled output: " + output);
    }

    @Test
    void modeCommandListsModesAndMarksCurrent() {
        current.setWorkflowMode(SessionMode.LONG_RUNNING);

        var action = handler.handle("/mode", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        String output = outBytes.toString();
        assertTrue(output.contains("Modes:"));
        assertTrue(output.contains("* long-running"));
        assertTrue(output.contains("common"));
    }

    @Test
    void modeCommandUsesChooserWhenAvailable() {
        SessionContext sessionContext = new SessionContext();
        handler = SlashCommandHandler.builder(storage, new TextScreen(out))
                .providerRegistry(createTestRegistry())
                .sessionContext(sessionContext)
                .modeChooser(modes -> Optional.of("common"))
                .registry(SlashCommandRegistry.create(null))
                .build();

        var action = handler.handle("/mode", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        assertEquals(PermissionMode.DEFAULT, current.permissionMode());
        assertEquals(SessionMode.COMMON, sessionContext.mode());
        assertTrue(stripAnsi(outBytes.toString()).contains("Mode set to: common"));
    }

    @Test
    void modeCommandCancelOutputIsDimmedAndSeparatedFromNextPrompt() {
        handler = SlashCommandHandler.builder(storage, new TextScreen(out))
                .providerRegistry(createTestRegistry())
                .modeChooser(modes -> Optional.empty())
                .registry(SlashCommandRegistry.create(null))
                .build();

        var action = handler.handle("/mode", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        String output = outBytes.toString();
        assertTrue(output.startsWith(System.lineSeparator()), "expected leading blank line: " + output);
        assertTrue(stripAnsi(output).contains("Mode selection cancelled."));
        assertTrue(output.contains("\u001B["), "expected styled output: " + output);
    }

    @Test
    void modeCommandLongRunningCreatesFreshControlSession() {
        SessionContext sessionContext = new SessionContext();
        handler = SlashCommandHandler.builder(storage, new TextScreen(out))
                .providerRegistry(createTestRegistry())
                .sessionContext(sessionContext)
                .registry(SlashCommandRegistry.create(null))
                .build();

        var action = handler.handle("/mode long-running", current);

        assertInstanceOf(SlashAction.SwitchToNewLongRunningSession.class, action);
        var switched = (SlashAction.SwitchToNewLongRunningSession) action;
        assertEquals(SessionMode.LONG_RUNNING, switched.session().workflowMode());
        assertEquals(madacode.core.session.LongRunningStage.DRAFT, switched.session().longRunningStage());
        assertEquals(PermissionMode.BYPASS, switched.session().permissionMode());
        assertEquals(false, switched.session().isPlanMode());
        assertTrue(switched.session().longRunningTaskId() != null);
        assertTrue(switched.session().longRunningTaskDirectory() != null);
        String output = outBytes.toString();
        String plain = stripAnsi(output);
        assertTrue(plain.contains("Entered long-running mode in a fresh control session."));
        assertTrue(plain.contains("State starts in DRAFT"));
        assertTrue(plain.contains("Current permission is all-pass"));
        assertTrue(output.contains("\u001B["), "expected styled output: " + output);
        assertTrue(switched.session().messages().stream()
                .anyMatch(message -> message.role() == MessageRole.SYSTEM
                        && firstText(message).contains("[long-running mode entered]")));
    }

    @Test
    void modeCommandCommonDoesNotResetPermission() {
        current.setWorkflowMode(SessionMode.LONG_RUNNING);
        current.setPermissionMode(PermissionMode.ACCEPT_EDITS);

        var action = handler.handle("/mode common", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        assertEquals(PermissionMode.ACCEPT_EDITS, current.permissionMode());
        assertEquals(SessionMode.COMMON, current.workflowMode());
        assertTrue(stripAnsi(outBytes.toString()).contains("Mode set to: common"));
    }

    @Test
    void modeCommandLongRunningStartsFreshTaskState() {
        current.setWorkflowMode(SessionMode.LONG_RUNNING);
        current.setLongRunningStage(madacode.core.session.LongRunningStage.EXECUTING);
        current.setLongRunningTaskId("task-old");
        current.setLongRunningTaskDirectory(tempDir.resolve("old").toString());
        current.setLongRunningTaskTitle("Old task");
        current.setLongRunningPlanSummary("Old plan");
        current.setPlanMode(true);

        var action = handler.handle("/mode long-running", current);

        assertInstanceOf(SlashAction.SwitchToNewLongRunningSession.class, action);
        var switched = (SlashAction.SwitchToNewLongRunningSession) action;
        assertEquals(SessionMode.LONG_RUNNING, switched.session().workflowMode());
        assertEquals(madacode.core.session.LongRunningStage.DRAFT,
                switched.session().longRunningStage());
        assertEquals(false, switched.session().isPlanMode());
        assertTrue(switched.session().longRunningTaskId() != null);
        assertTrue(switched.session().longRunningTaskDirectory() != null);
        assertEquals(null, switched.session().longRunningTaskTitle());
        assertEquals(null, switched.session().longRunningPlanSummary());
    }

    @Test
    void longRunContinueCommandReturnsLaunchWhenTaskActive() {
        current.setWorkflowMode(SessionMode.LONG_RUNNING);
        current.setLongRunningStage(madacode.core.session.LongRunningStage.EXECUTING);
        current.setLongRunningTaskId("task-test");

        var action = handler.handle("/longrun-continue 3", current);

        assertInstanceOf(SlashAction.LongRunLaunch.class, action);
        assertEquals(3, ((SlashAction.LongRunLaunch) action).maxWorkers());
    }

    @Test
    void longRunContinueCommandDefaultsMaxWorkers() {
        current.setWorkflowMode(SessionMode.LONG_RUNNING);
        current.setLongRunningStage(madacode.core.session.LongRunningStage.EXECUTING);
        current.setLongRunningTaskId("task-test");

        var action = handler.handle("/longrun-continue", current);

        assertInstanceOf(SlashAction.LongRunLaunch.class, action);
        assertEquals(5, ((SlashAction.LongRunLaunch) action).maxWorkers());
    }

    @Test
    void longRunContinueCommandRejectsInvalidMaxWorkers() {
        current.setWorkflowMode(SessionMode.LONG_RUNNING);
        current.setLongRunningStage(madacode.core.session.LongRunningStage.EXECUTING);
        current.setLongRunningTaskId("task-test");

        var action = handler.handle("/longrun-continue 0", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        assertTrue(stripAnsi(outBytes.toString()).contains("max-workers"));
    }

    @Test
    void longRunContinueCommandRejectsWhenNoTaskId() {
        current.setWorkflowMode(SessionMode.LONG_RUNNING);
        current.setLongRunningStage(madacode.core.session.LongRunningStage.EXECUTING);
        // No taskId set

        var action = handler.handle("/longrun-continue 3", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        assertTrue(stripAnsi(outBytes.toString()).contains("No active long-running task"));
    }

    @Test
    void modeCommandUnknownModeDoesNotChangeSession() {
        current.setWorkflowMode(SessionMode.COMMON);
        current.setPermissionMode(PermissionMode.ACCEPT_EDITS);

        var action = handler.handle("/mode nope", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        assertEquals(PermissionMode.ACCEPT_EDITS, current.permissionMode());
        assertEquals(SessionMode.COMMON, current.workflowMode());
        assertEquals(false, current.isPlanMode());
        assertTrue(outBytes.toString().contains("Unknown mode: nope"));
    }

    @Test
    void permissionCommandListsPermissionsAndMarksCurrent() {
        current.setPermissionMode(PermissionMode.BYPASS);

        var action = handler.handle("/permission", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        String output = outBytes.toString();
        assertTrue(output.contains("Permissions:"));
        assertTrue(output.contains("* all-pass"));
        assertTrue(output.contains("strict"));
        assertTrue(output.contains("normal"));
    }

    @Test
    void permissionCommandUsesChooserWhenAvailable() {
        SessionContext sessionContext = new SessionContext();
        handler = SlashCommandHandler.builder(storage, new TextScreen(out))
                .providerRegistry(createTestRegistry())
                .sessionContext(sessionContext)
                .permissionChooser(modes -> Optional.of("normal"))
                .registry(SlashCommandRegistry.create(null))
                .build();

        var action = handler.handle("/permission", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        assertEquals(PermissionMode.ACCEPT_EDITS, current.permissionMode());
        assertEquals(PermissionMode.ACCEPT_EDITS, sessionContext.permissionMode());
        assertTrue(stripAnsi(outBytes.toString()).contains("Permission set to: normal"));
    }

    @Test
    void permissionCommandExplicitModesSetExpectedPermissionMode() {
        var strictAction = handler.handle("/permission strict", current);
        assertInstanceOf(SlashAction.Handled.class, strictAction);
        assertEquals(PermissionMode.DEFAULT, current.permissionMode());

        var normalAction = handler.handle("/permission normal", current);
        assertInstanceOf(SlashAction.Handled.class, normalAction);
        assertEquals(PermissionMode.ACCEPT_EDITS, current.permissionMode());

        var allPassAction = handler.handle("/permission all-pass", current);
        assertInstanceOf(SlashAction.Handled.class, allPassAction);
        assertEquals(PermissionMode.BYPASS, current.permissionMode());
        assertTrue(stripAnsi(outBytes.toString()).contains("Permission set to: all-pass"));
        assertTrue(stripAnsi(outBytes.toString()).contains("Warning: all-pass suppresses interactive approval"));
    }

    @Test
    void permissionCommandCancelOutputIsDimmedAndSeparatedFromNextPrompt() {
        handler = SlashCommandHandler.builder(storage, new TextScreen(out))
                .providerRegistry(createTestRegistry())
                .permissionChooser(modes -> Optional.empty())
                .registry(SlashCommandRegistry.create(null))
                .build();

        var action = handler.handle("/permission", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        String output = outBytes.toString();
        assertTrue(output.startsWith(System.lineSeparator()), "expected leading blank line: " + output);
        assertTrue(stripAnsi(output).contains("Permission selection cancelled."));
        assertTrue(output.contains("\u001B["), "expected styled output: " + output);
    }

    @Test
    void permissionCommandUnknownModeDoesNotChangeSession() {
        current.setPermissionMode(PermissionMode.ACCEPT_EDITS);

        var action = handler.handle("/permission nope", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        assertEquals(PermissionMode.ACCEPT_EDITS, current.permissionMode());
        assertTrue(outBytes.toString().contains("Unknown permission mode: nope"));
    }

    @Test
    void providerCommandSetOutputIsDimmedAndSeparatedFromNextPrompt() {
        var action = handler.handle("/provider test", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        String output = outBytes.toString();
        assertTrue(output.startsWith(System.lineSeparator()), "expected leading blank line: " + output);
        assertTrue(stripAnsi(output).contains("Provider set to: test (model: claude-opus-4-7)"));
        assertTrue(output.contains("\u001B["), "expected styled output: " + output);
    }

    @Test
    void providerCommandCancelOutputIsDimmedAndSeparatedFromNextPrompt() {
        handler = SlashCommandHandler.builder(storage, new TextScreen(out))
                .providerRegistry(createTestRegistry())
                .providerChooser(providers -> Optional.empty())
                .registry(SlashCommandRegistry.create(null))
                .build();

        var action = handler.handle("/provider", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        String output = outBytes.toString();
        assertTrue(output.startsWith(System.lineSeparator()), "expected leading blank line: " + output);
        assertTrue(stripAnsi(output).contains("Provider selection cancelled."));
        assertTrue(output.contains("\u001B["), "expected styled output: " + output);
    }

    @Test
    void themeCommandSetOutputIsDimmedAndSeparatedFromNextPrompt() {
        var action = handler.handle("/theme dark", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        String output = outBytes.toString();
        assertTrue(output.startsWith(System.lineSeparator()), "expected leading blank line: " + output);
        assertTrue(stripAnsi(output).contains("Theme set to: dark"));
        assertTrue(output.contains("\u001B["), "expected styled output: " + output);
    }

    @Test
    void themeCommandCancelOutputIsDimmedAndSeparatedFromNextPrompt() {
        handler = SlashCommandHandler.builder(storage, new TextScreen(out))
                .providerRegistry(createTestRegistry())
                .themeChooser(themes -> Optional.empty())
                .registry(SlashCommandRegistry.create(null))
                .build();

        var action = handler.handle("/theme", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        String output = outBytes.toString();
        assertTrue(output.startsWith(System.lineSeparator()), "expected leading blank line: " + output);
        assertTrue(stripAnsi(output).contains("Theme selection cancelled."));
        assertTrue(output.contains("\u001B["), "expected styled output: " + output);
    }

    @Test
    void compactCommandDegradesWhenPlannerUnavailable() {
        var action = handler.handle("/compact", current);
        assertInstanceOf(SlashAction.Handled.class, action);
        assertTrue(outBytes.toString().contains("Compaction is not available"));
    }

    @Test
    void compactCommandReturnsLocalTurnWhenPlannerAvailable() {
        handler = SlashCommandHandler.builder(storage, new TextScreen(out))
                .providerRegistry(createTestRegistry())
                .registry(SlashCommandRegistry.create(null))
                .compactPlanner(new CompactPlanner(
                        new TokenEstimator(),
                        CompactBudget.defaults(),
                        List.of()))
                .build();

        var action = handler.handle("/compact", current);

        SlashAction.RunLocalTurn run = assertInstanceOf(SlashAction.RunLocalTurn.class, action);
        assertEquals("slash:/compact", run.label());
        assertEquals("", outBytes.toString());
    }

    @Test
    void costCommandShowsAccumulatedTokenUsage() {
        current.fireMetaEvent(new MetaEvent.TokenReport(new TokenUsage(10, 20, 30, 40), 1, 2));

        var action = handler.handle("/cost", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        String output = outBytes.toString();
        assertTrue(output.contains("input"));
        assertTrue(output.contains("10"));
        assertTrue(output.contains("Token usage"));
    }

    @Test
    void statusCommandShowsSessionSummary() {
        current.setPermissionMode(PermissionMode.ACCEPT_EDITS);

        var action = handler.handle("/status", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        String output = stripAnsi(outBytes.toString());
        assertTrue(output.contains("session"));
        assertTrue(output.contains("current"));
        assertTrue(output.contains("messages"));
        assertTrue(output.contains("mode common"));
        assertTrue(output.contains("permission normal"));
        assertTrue(!output.contains("configured at startup"));
        assertTrue(!output.contains("active gate"));
    }

    @Test
    void themeCommandListsThemesWithoutInteractiveChooser() {
        var action = handler.handle("/theme", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        assertTrue(outBytes.toString().contains("dark"));
    }

    @Test
    void helpCommandCanShowOneCommand() {
        var action = handler.handle("/help status", current);

        assertInstanceOf(SlashAction.Handled.class, action);
        assertTrue(outBytes.toString().contains("Usage: /status"));
    }

    @Test
    void replayAllCommandReturnsReplayAction() {
        var action = handler.handle("/replay-all", current);

        assertInstanceOf(SlashAction.ReplayAll.class, action);
    }

    @Test
    void unknownCommand() {
        var action = handler.handle("/foobar", current);
        assertInstanceOf(SlashAction.Handled.class, action);
        assertTrue(outBytes.toString().contains("Unknown command"));
    }

    private static ConversationSession newSession(String id, String firstUserMessage) {
        return new ConversationSession(
                id,
                Instant.now(),
                Path.of("."),
                List.of(Message.system("Init"), Message.user(firstUserMessage)));
    }

    private static String firstText(Message message) {
        if (message.contentBlocks().isEmpty()) {
            return "";
        }
        var first = message.contentBlocks().getFirst();
        return first instanceof madacode.core.model.ContentBlock.TextBlock text
                ? text.text()
                : "";
    }

    private static SkillLoader testSkillLoader() {
        return () -> List.of(
                new Skill("alpha", "Alpha skill", "", List.of(), SkillSource.BUNDLED,
                        "alpha body", Path.of("alpha/SKILL.md"), Path.of("alpha"),
                        "inline", List.of(), List.of(), 1, 1),
                new Skill("beta", "Beta skill", "", List.of(), SkillSource.PROJECT,
                        "beta body", Path.of("beta/SKILL.md"), Path.of("beta"),
                        "inline", List.of(), List.of(), 1, 1));
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "");
    }
}
