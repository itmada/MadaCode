package madacode.storage;

import java.nio.file.Path;
import java.util.Objects;

public record RuntimePaths(
        Path homeDir,
        Path projectDir,
        WorkspaceIdentity workspace) {

    public RuntimePaths {
        homeDir = Objects.requireNonNull(homeDir, "homeDir")
                .toAbsolutePath()
                .normalize();
        projectDir = Objects.requireNonNull(projectDir, "projectDir")
                .toAbsolutePath()
                .normalize();
        workspace = Objects.requireNonNull(workspace, "workspace");
    }

    public static RuntimePaths forProject(Path homeDir, Path projectDir) {
        return new RuntimePaths(homeDir, projectDir, WorkspaceIdentity.from(projectDir));
    }

    public Path globalHome() {
        return homeDir.resolve(".mada");
    }

    public Path globalProvidersFile() {
        return globalHome().resolve("providers.json");
    }

    public Path globalStateFile() {
        return globalHome().resolve("state.json");
    }

    public Path globalMcpConfigFile() {
        return globalHome().resolve("mcp.json");
    }

    public Path globalHooksFile() {
        return globalHome().resolve("hooks.json");
    }

    public Path globalSkillsStateFile() {
        return globalHome().resolve("skills.json");
    }

    public Path globalSkillsDir() {
        return globalHome().resolve("skills");
    }

    public Path globalAgentsDir() {
        return globalHome().resolve("agents");
    }

    public Path globalBlobsDir() {
        return globalHome().resolve("blobs");
    }

    public Path globalMemoryDir() {
        return globalHome().resolve("memory");
    }

    public Path workspaceRoot() {
        return globalHome().resolve("projects").resolve(workspace.key());
    }

    public Path workspaceSessionsDir() {
        return workspaceRoot().resolve("sessions");
    }

    public Path workspaceLastSessionFile() {
        return workspaceRoot().resolve("last-session");
    }

    public Path workspaceDebugDir() {
        return workspaceRoot().resolve("debug");
    }

    public Path workspaceModelResponsesDir() {
        return workspaceDebugDir().resolve("model-responses");
    }

    public Path workspaceMemoryDir() {
        return workspaceRoot().resolve("memory");
    }

    public Path workspaceMemoryFile() {
        return workspaceMemoryDir().resolve("MEMORY.md");
    }

    public Path workspacePermissionsDir() {
        return workspaceRoot().resolve("permissions");
    }

    public Path workspacePermissionAuditFile() {
        return workspacePermissionsDir().resolve("audit.jsonl");
    }
}
