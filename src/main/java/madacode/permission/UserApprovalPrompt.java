package madacode.permission;

import madacode.tool.Tool;

/**
 * Abstraction over the user-approval interaction.
 *
 * <p>Separated from {@link PermissionGate} so that the gate's logic can be
 * unit-tested with a mock prompt, and so different UIs (terminal, web, IDE)
 * can plug in their own interaction style.
 */
@FunctionalInterface
public interface UserApprovalPrompt {

    /**
     * Ask the user whether to allow a tool invocation.
     *
     * @param tool  the tool requesting permission
     * @param input the tool input that triggered the request
     * @return the user's approval decision
     */
    ApprovalResponse requestApproval(Tool<?> tool, String input);
}
