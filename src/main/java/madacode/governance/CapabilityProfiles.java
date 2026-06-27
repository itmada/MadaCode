package madacode.governance;

import madacode.tool.access.ToolCapabilityProfile;

/**
 * Central constructors for common runtime governance roles.
 */
public final class CapabilityProfiles {

    private CapabilityProfiles() {
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
