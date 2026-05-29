package madacode.agent;

import madacode.core.QueryEngine;
import madacode.permission.PermissionMode;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

public final class BuiltInAgents {

    private BuiltInAgents() {
    }

    public static AgentDefinition explorer() {
        return new AgentDefinition(
                "explorer",
                "Explores files and summarizes project structure.",
                "Finding files by name pattern, searching code for symbols or keywords, answering 'where is X defined' or 'which files reference Y'.",
                "You are a code exploration agent. Search the codebase to answer questions about structure, dependencies, and implementation details. Use file_read to inspect files, glob to find files by pattern, and grep to search code. Report findings clearly and concisely. Never modify files or execute commands.",
                Set.of("file_read", "glob", "grep"),
                Set.of("agent", "bash"),
                QueryEngine.DEFAULT_MAX_ITERATIONS,
                20,
                PermissionMode.ACCEPT_EDITS
        );
    }

    public static AgentDefinition planner() {
        return new AgentDefinition(
                "planner",
                "Plans implementation strategies and analyzes architecture.",
                "Planning implementation strategy, analyzing architectural trade-offs, reviewing design patterns, risk assessment for proposed changes.",
                "You are a software architecture planning agent. Analyze code structure, design patterns, and architectural decisions. Use file_read to inspect files, glob to discover project structure, and grep to trace dependencies. Provide clear analysis and actionable recommendations. Never modify files or execute commands.",
                Set.of("file_read", "glob", "grep"),
                Set.of("agent", "bash"),
                4,
                12,
                PermissionMode.ACCEPT_EDITS
        );
    }

    public static AgentDefinition general() {
        return new AgentDefinition(
                "general",
                "General-purpose sub-agent for executing subtasks.",
                "General subtasks that may require running commands, reading files, or searching code.",
                "You are a general-purpose sub-agent. Execute the given task using available tools. You can read files, search code, and run shell commands. Work independently and report results clearly.",
                Set.of("file_read", "glob", "grep", "bash"),
                Set.of("agent"),
                8,
                30,
                PermissionMode.ACCEPT_EDITS
        );
    }

    public static List<AgentDefinition> getAll() {
        return List.of(explorer(), planner(), general());
    }

    public static Optional<AgentDefinition> findByType(String agentType) {
        if (agentType == null || agentType.isBlank()) {
            return Optional.empty();
        }
        String normalized = agentType.strip().toLowerCase(Locale.ROOT);
        return getAll().stream()
                .filter(def -> def.agentType().toLowerCase(Locale.ROOT).equals(normalized))
                .findFirst();
    }
}
