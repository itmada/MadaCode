package madacode.governance;

import madacode.permission.PermissionMode;
import madacode.tool.ToolNames;
import madacode.tool.access.ToolCapabilityProfile;

import java.util.Objects;
import java.util.Set;

/**
 * Central constructors for common runtime governance roles.
 */
public final class CapabilityProfiles {

    public static final Set<String> LONG_RUNNING_WORKER_TOOLS = Set.of(
            ToolNames.FILE_READ,
            ToolNames.GLOB,
            ToolNames.GREP,
            ToolNames.FILE_WRITE,
            ToolNames.FILE_EDIT,
            ToolNames.BASH,
            ToolNames.UPDATE_PLAN,
            ToolNames.LONGRUN_ENVIRONMENT_READ,
            ToolNames.LONGRUN_ENVIRONMENT_UPDATE,
            ToolNames.WORKER_REPORT);

    private CapabilityProfiles() {
    }

    public static CapabilityProfile mainSession(PermissionMode permissionMode, IsolationProfile isolationProfile) {
        PermissionMode mode = permissionMode == null ? PermissionMode.DEFAULT : permissionMode;
        return mainSession(mode.approvalPosture(), isolationProfile);
    }

    public static CapabilityProfile mainSession(
            ApprovalPosture approvalPosture,
            IsolationProfile isolationProfile) {
        return new CapabilityProfile(
                "main",
                ToolCapabilityProfile.unrestricted(),
                Objects.requireNonNull(approvalPosture, "approvalPosture"),
                Objects.requireNonNull(isolationProfile, "isolationProfile"));
    }

    public static CapabilityProfile longRunningWorker(IsolationProfile isolationProfile) {
        return longRunningWorker(ToolCapabilityProfile.explicitAllowList(
                "longrun-worker",
                LONG_RUNNING_WORKER_TOOLS,
                false),
                isolationProfile);
    }

    public static CapabilityProfile longRunningWorker(
            ToolCapabilityProfile toolCapability,
            IsolationProfile isolationProfile) {
        return new CapabilityProfile(
                "longrun-worker",
                Objects.requireNonNull(toolCapability, "toolCapability"),
                ApprovalPosture.longRunningWorker(),
                Objects.requireNonNull(isolationProfile, "isolationProfile"));
    }

    public static CapabilityProfile evalCase(IsolationProfile isolationProfile) {
        return new CapabilityProfile(
                "eval",
                ToolCapabilityProfile.unrestricted(),
                ApprovalPosture.defaultInteractive(),
                Objects.requireNonNull(isolationProfile, "isolationProfile"));
    }
}
