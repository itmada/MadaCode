package madacode.governance;

import madacode.permission.PermissionMode;
import madacode.tool.ToolNames;
import madacode.tool.access.ToolCapabilityProfile;

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

    public static CapabilityProfile mainSession(PermissionMode permissionMode) {
        PermissionMode mode = permissionMode == null ? PermissionMode.DEFAULT : permissionMode;
        return mainSession(mode.approvalPosture());
    }

    public static CapabilityProfile mainSession(ApprovalPosture approvalPosture) {
        return new CapabilityProfile(
                "main",
                ToolCapabilityProfile.unrestricted(),
                approvalPosture,
                IsolationProfile.localUnsafe());
    }

    public static CapabilityProfile subAgent(
            String id,
            ToolCapabilityProfile toolCapability,
            ApprovalPosture approvalPosture) {
        return new CapabilityProfile(
                id,
                toolCapability,
                approvalPosture,
                IsolationProfile.localUnsafe());
    }

    public static CapabilityProfile longRunningWorker() {
        return longRunningWorker(ToolCapabilityProfile.explicitAllowList(
                "longrun-worker",
                LONG_RUNNING_WORKER_TOOLS,
                false));
    }

    public static CapabilityProfile longRunningWorker(ToolCapabilityProfile toolCapability) {
        return new CapabilityProfile(
                "longrun-worker",
                toolCapability,
                ApprovalPosture.longRunningWorker(),
                IsolationProfile.localUnsafe());
    }

    public static CapabilityProfile evalCase(IsolationProfile isolationProfile) {
        return new CapabilityProfile(
                "eval",
                ToolCapabilityProfile.unrestricted(),
                ApprovalPosture.defaultInteractive(),
                isolationProfile);
    }
}
