package madacode.permission;

import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.ToolUseContext;
import madacode.events.AuditEvent;
import madacode.events.AppEvents;
import madacode.events.EventContext;
import madacode.logging.DiagnosticEventLogger;
import madacode.tool.Tool;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V1 default permission gate.
 *
 * <p>Decision pipeline:
 * <ol>
 *   <li><strong>Permission rules</strong> &rarr; auto-allow or deny known cases.</li>
 *   <li><strong>Session memory</strong> &rarr; if the user previously chose
 *       "allow for session", the same tool input auto-allows.</li>
 *   <li><strong>Interactive prompt</strong> &rarr; delegate to the injected
 *       {@link UserApprovalPrompt} for the final decision.</li>
 * </ol>
 *
 * <p>Deny rules run first, then tool-specific checks, then mode-based
 * overrides, then a default "ask the user" fallback.
 */
public class DefaultPermissionGate implements PermissionGate {

    public static final String SOURCE_SESSION_MEMORY = "session_memory";
    public static final String SOURCE_USER_PROMPT = "user_prompt";

    private final UserApprovalPrompt prompt;
    private final List<PermissionRule> rules;
    private final Set<String> sessionApprovedInputs;

    public DefaultPermissionGate(UserApprovalPrompt prompt) {
        this(prompt, defaultRules());
    }

    private DefaultPermissionGate(
            UserApprovalPrompt prompt,
            List<PermissionRule> rules) {
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        this.sessionApprovedInputs = ConcurrentHashMap.newKeySet();
    }

    @Override
    public PermissionDecision check(Tool<?> tool, ObjectNode input, ToolUseContext context) {
        for (PermissionRule rule : rules) {
            Optional<PermissionDecision> decision = rule.evaluate(tool, input, context);
            if (decision.isPresent()) {
                PermissionDecision d = decision.get();
                recordDecision(context, tool, input, d, 0);
                return d;
            }
        }

        String approvalKey = approvalKey(tool, input);
        if (sessionApprovedInputs.contains(approvalKey)) {
            PermissionDecision decision = PermissionDecision.allow(SOURCE_SESSION_MEMORY);
            recordDecision(context, tool, input, decision, 0);
            return decision;
        }

        long promptStart = System.nanoTime();
        ApprovalResponse response = prompt.requestApproval(tool, input.toString());
        long waitMs = java.time.Duration.ofNanos(System.nanoTime() - promptStart).toMillis();
        PermissionDecision userDecision = switch (response) {
            case ALLOW_SESSION -> {
                sessionApprovedInputs.add(approvalKey);
                yield PermissionDecision.allow(SOURCE_USER_PROMPT);
            }
            case ALLOW_ONCE -> PermissionDecision.allow(SOURCE_USER_PROMPT);
            case DENY -> PermissionDecision.deny(
                    "User denied permission for tool: " + tool.name(),
                    SOURCE_USER_PROMPT);
        };
        recordDecision(context, tool, input, userDecision, waitMs);
        return userDecision;
    }

    private static List<PermissionRule> defaultRules() {
        return List.of(
                new BashSafetyPermissionRule(),
                new ReadOnlyPermissionRule(),
                new BypassPermissionRule(),
                new AcceptEditsPermissionRule());
    }

    private String approvalKey(Tool<?> tool, ObjectNode input) {
        return tool.name() + ":" + tool.approvalSignature(input);
    }

    private void recordDecision(
            ToolUseContext context,
            Tool<?> tool,
            ObjectNode input,
            PermissionDecision decision,
            long waitMs) {
        DiagnosticEventLogger.permissionDecision(context.session(), tool.name(), decision, waitMs);
        AppEvents.publisher().publish(AuditEvent.permissionDecision(
                EventContext.of(context.session(), "Permission"),
                tool.name(),
                decision.isAllowed(),
                decision.reason(),
                decision.source(),
                waitMs,
                truncate(input.toString(), 500)));
    }

    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
