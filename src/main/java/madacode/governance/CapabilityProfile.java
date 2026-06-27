package madacode.governance;

import madacode.tool.access.ToolCapabilityProfile;

/**
 * One execution context's governance declaration. Tool access, approval, and
 * execution isolation consume different projections of the same profile.
 */
public record CapabilityProfile(
        String id,
        ToolCapabilityProfile toolCapability,
        ApprovalPosture approvalPosture,
        IsolationProfile isolationProfile) {
}
