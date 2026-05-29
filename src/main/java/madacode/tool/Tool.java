package madacode.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import madacode.core.ToolResult;
import madacode.core.ToolUseContext;

/**
 * Strongly-typed tool contract.
 *
 * <p>Each tool declares the shape of its input as a Java record (or any
 * Jackson-deserialisable type) via {@link #inputType()}. The executor
 * deserialises the model's JSON tool_use {@code input} block into that
 * type once, then hands the instance to {@link #execute(Object, ToolUseContext)}.
 *
 * <p>Mirrors the upstream TypeScript {@code Tool<I, O>} contract
 * ({@code src/Tool.ts}) but uses Java's static type system to surface
 * field-name typos and missing fields at compile time, where the original
 * relies on Zod's runtime schema validation.
 *
 * @param <I> the tool's input type — typically a record. Must be
 *            deserialisable by Jackson from the model's tool_use input
 *            object.
 */
public interface Tool<I> {

    String name();

    String description();

    /**
     * The Jackson-deserialisable input type. Used by {@code ToolExecutor}
     * to convert the raw {@link ObjectNode} from the API into a typed
     * value before invoking {@link #execute(Object, ToolUseContext)}.
     */
    Class<I> inputType();

    /**
     * Whether this tool only reads from the environment. Tools that mutate
     * the filesystem, session state, or the outside world return
     * {@code false}. The permission gate, plan-mode filter, and concurrency
     * orchestrator all read this.
     */
    boolean isReadOnly();

    /**
     * Whether this tool's primary effect is modifying user files in the
     * working directory. The {@code AcceptEditsPermissionRule} reads this
     * to decide whether to auto-allow in {@link madacode.permission.PermissionMode#ACCEPT_EDITS}.
     *
     * <p>Defaults to {@code false}; only file edit/write tools opt in.
     * Tools that modify session state, run commands, or call external
     * services must return {@code false} — they should still surface to
     * the user for approval.
     */
    default boolean isFileEdit() {
        return false;
    }

    /**
     * Whether this tool is safe to execute concurrently with other tools
     * in the same model turn. Defaults to {@code false} — only tools with
     * no side effects on session state, files, or shared external resources
     * should opt in.
     *
     * <p>Note that {@link #isReadOnly()} is <em>not</em> equivalent: tools
     * like AgentTool, ask_user_question, or enter_plan_mode are "read-only"
     * w.r.t. the filesystem but mutate session state or block on I/O, and
     * therefore must run serially.
     *
     * <p>The {@code input} parameter is provided so a tool can decide based
     * on its arguments — e.g. {@code bash ls -la} could be concurrency-safe
     * while {@code bash rm -rf /} is not. Most tools ignore it and return a
     * constant.
     */
    default boolean isConcurrencySafe(I input) {
        return false;
    }

    ObjectNode inputSchema(ObjectMapper mapper);

    ToolResult execute(I input, ToolUseContext context);

    /**
     * Stable signature used by the permission gate to decide whether two
     * tool invocations are "the same" for approval-caching purposes.
     *
     * <p>Default implementation canonicalises the JSON input (sorted keys).
     * This handles the common case where the model emits fields in a
     * different order between calls but the semantics are identical.
     *
     * <p>Tools that have a single semantically-meaningful field should
     * override and return just that field — e.g. {@code BashTool} approves
     * by command string only, not by description or timeout.
     */
    default String approvalSignature(ObjectNode input) {
        return madacode.permission.CanonicalJson.canonicalize(input);
    }
}
