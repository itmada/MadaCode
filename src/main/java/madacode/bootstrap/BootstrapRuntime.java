package madacode.bootstrap;

import madacode.agent.AgentRegistry;
import madacode.cli.InterruptController;
import madacode.cli.UserPromptChannel;
import madacode.cli.editor.SessionHistory;
import madacode.cli.slash.SlashCommandRegistry;
import madacode.core.session.ConversationSession;
import madacode.core.engine.QueryEngine;
import madacode.core.session.SessionStorage;
import madacode.core.turn.TurnExecutor;
import madacode.events.AppEventPublisher;
import madacode.mcp.McpConnectionManager;
import madacode.memory.MemoryLoader;
import madacode.permission.JLineApprovalPrompt;
import madacode.permission.PermissionGate;
import madacode.provider.ProviderRegistry;
import madacode.services.api.ApiClient;
import madacode.services.compact.CompactPlanner;
import madacode.skill.SkillRegistry;
import madacode.tool.ToolRegistry;
import madacode.tui.JLineScreen;

import org.jline.reader.LineReader;
import org.jline.terminal.Terminal;

import java.nio.file.Path;

record EnvironmentRuntime(
        madacode.cli.CliArgs args,
        ProviderRegistry providerRegistry,
        madacode.provider.ProviderLoader providerLoader,
        ApiClient api,
        Path homeDir,
        Path projectDir,
        boolean memoryEnabled) {}

record TerminalRuntime(
        Terminal terminal,
        JLineScreen screen,
        InterruptController interrupts,
        JLineApprovalPrompt approval) {

    boolean interactive() {
        return terminal != null;
    }
}

record EventsRuntime(
        AppEventPublisher publisher,
        ForegroundSessionTracker foreground) {}

record ToolRuntime(
        ToolRegistry registry,
        MemoryLoader memory,
        McpConnectionManager mcpManager,
        SkillRegistry skillRegistry,
        AgentRegistry agentRegistry) {}

record EngineRuntime(
        QueryEngine engine,
        PermissionGate permission,
        CompactPlanner compaction) {}

record SessionRuntime(
        SessionStorage storage,
        ConversationSession session) {}

record InteractionRuntime(
        SlashCommandRegistry slashRegistry,
        UserPromptChannel channel,
        LineReader lineReader,
        SessionHistory sessionHistory,
        TurnExecutor turnExecutor) {}
