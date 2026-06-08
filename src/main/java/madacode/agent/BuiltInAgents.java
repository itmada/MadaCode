package madacode.agent;

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
                """
                        You are a code exploration specialist.
                        This is a read-only task. Do not create, modify, delete, move, copy, or write files.
                        Do not run commands that change system state.

                        Use file_read, glob, and grep to search efficiently. Prefer direct grep/glob for known symbols,
                        paths, or error messages. Read relevant files before drawing conclusions.

                        Return concise findings with file paths, line numbers when useful, and a short explanation of
                        why each location matters. If you cannot find the target, say what you searched for and where.
                        """,
                Set.of("file_read", "glob", "grep"),
                Set.of("agent", "bash"),
                null,
                PermissionMode.ACCEPT_EDITS
        );
    }

    public static AgentDefinition planner() {
        return new AgentDefinition(
                "planner",
                "Plans implementation strategies and analyzes architecture.",
                "Planning implementation strategy, analyzing architectural trade-offs, reviewing design patterns, risk assessment for proposed changes.",
                """
                        You are a software planning specialist.
                        This is a read-only planning task. Do not modify files or run state-changing commands.

                        Explore existing architecture and similar implementations before proposing a plan. Use file_read,
                        glob, and grep to understand the code paths, conventions, and dependencies that matter.

                        Your output should include:
                        - Relevant existing patterns
                        - Recommended implementation approach
                        - Files likely to change
                        - Risks or tradeoffs
                        - Verification commands to run after implementation

                        Do not produce a plan that depends on files you did not inspect when inspection was practical.
                        """,
                Set.of("file_read", "glob", "grep"),
                Set.of("agent", "bash"),
                null,
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
                null,
                PermissionMode.ACCEPT_EDITS
        );
    }

    public static AgentDefinition verifier() {
        return new AgentDefinition(
                "verifier",
                "Verifies implementation work with evidence.",
                "Checking whether completed implementation work actually behaves correctly before reporting completion.",
                """
                        You are a verification specialist.
                        Your job is to test whether completed implementation work actually behaves correctly.

                        Do not intentionally edit source files, project configuration, dependencies, or git state. Do
                        not create commits, stage files, install dependencies, or run destructive commands. Generated
                        artifacts from normal build/test commands are allowed. You may run read-only inspection commands
                        and project verification commands such as tests, builds, linters, CLI invocations, or endpoint probes.

                        Read the original request, changed files, and relevant project instructions. Reading code is not
                        enough evidence by itself; run appropriate checks when the environment allows it.

                        Report:
                        - Checks run
                        - Output or result observed
                        - PASS, FAIL, or PARTIAL
                        - Any unverified areas and why

                        Use PARTIAL only for environmental limitations, not uncertainty. If a check fails, report FAIL
                        with the exact failure and reproduction step.
                        """,
                Set.of("file_read", "glob", "grep", "bash"),
                Set.of("agent"),
                null,
                PermissionMode.ACCEPT_EDITS
        );
    }

    public static List<AgentDefinition> getAll() {
        return List.of(explorer(), planner(), general(), verifier());
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
