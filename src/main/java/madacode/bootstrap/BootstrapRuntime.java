package madacode.bootstrap;

import madacode.agent.AgentRegistry;
import madacode.cli.InterruptController;
import madacode.cli.session.SessionPointer;
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
import madacode.logging.DiagnosticEvents;
import madacode.services.api.ApiClient;
import madacode.services.compact.CompactPlanner;
import madacode.skill.SkillRegistry;
import madacode.tool.access.ToolAccessResolver;
import madacode.storage.RuntimePaths;
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
        RuntimePaths paths,
        boolean memoryEnabled,
        DiagnosticEvents diagnosticEvents) {}

record TerminalRuntime(
        Terminal terminal,
        JLineScreen screen,
        InterruptController interrupts,
        JLineApprovalPrompt approval) {}

record EventsRuntime(
        AppEventPublisher publisher,
        ForegroundSessionTracker foreground) {}

record ToolRuntime(
        ToolRegistry registry,
        ToolAccessResolver toolAccessResolver,
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
        SessionPointer pointer,
        ConversationSession session) {}

record InteractionRuntime(
        SlashCommandRegistry slashRegistry,
        UserPromptChannel channel,
        LineReader lineReader,
        SessionHistory sessionHistory,
        TurnExecutor turnExecutor) {}
