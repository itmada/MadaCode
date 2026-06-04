package madacode.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CliArgsTest {

    @Test
    void noArgsIsInteractive() {
        CliArgs args = CliArgs.parse(new String[0]);
        assertTrue(args instanceof CliArgs.Interactive);
    }

    @Test
    void newFlag() {
        CliArgs args = CliArgs.parse(new String[]{"--new"});
        assertTrue(args instanceof CliArgs.NewSession);
    }

    @Test
    void longRunningFlag() {
        CliArgs args = CliArgs.parse(new String[]{"--long-running"});
        assertTrue(args instanceof CliArgs.LongRunningSession);
    }

    @Test
    void continueShort() {
        CliArgs args = CliArgs.parse(new String[]{"-c"});
        assertTrue(args instanceof CliArgs.Continue);
    }

    @Test
    void continueLong() {
        CliArgs args = CliArgs.parse(new String[]{"--continue"});
        assertTrue(args instanceof CliArgs.Continue);
    }

    @Test
    void listShort() {
        CliArgs args = CliArgs.parse(new String[]{"-l"});
        assertTrue(args instanceof CliArgs.ListSessions);
    }

    @Test
    void listLong() {
        CliArgs args = CliArgs.parse(new String[]{"--list"});
        assertTrue(args instanceof CliArgs.ListSessions);
    }

    @Test
    void resumeWithId() {
        CliArgs args = CliArgs.parse(new String[]{"--resume", "abc123"});
        assertTrue(args instanceof CliArgs.Resume r && r.sessionId().equals("abc123"));
    }

    @Test
    void resumeShortFlag() {
        CliArgs args = CliArgs.parse(new String[]{"-r", "xyz789"});
        assertTrue(args instanceof CliArgs.Resume r && r.sessionId().equals("xyz789"));
    }

    @Test
    void resumeEqualsSyntax() {
        CliArgs args = CliArgs.parse(new String[]{"--resume=abc"});
        assertTrue(args instanceof CliArgs.Resume r && r.sessionId().equals("abc"));
    }

    @Test
    void helpShort() {
        assertTrue(CliArgs.parse(new String[]{"-h"}) instanceof CliArgs.Help);
    }

    @Test
    void helpLong() {
        assertTrue(CliArgs.parse(new String[]{"--help"}) instanceof CliArgs.Help);
    }

    @Test
    void resumeWithoutIdThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                CliArgs.parse(new String[]{"--resume"}));
    }

    @Test
    void resumeEqualsBlankIdThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                CliArgs.parse(new String[]{"--resume=  "}));
    }

    @Test
    void unknownFlagThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                CliArgs.parse(new String[]{"--verbose"}));
    }

    @Test
    void tooManyArgsUsesFirstValue() {
        // Extra positional args are ignored; only the first value is taken.
        CliArgs args = CliArgs.parse(new String[]{"--resume", "a", "b"});
        assertTrue(args instanceof CliArgs.Resume r && r.sessionId().equals("a"));
    }

    @Test
    void noMemoryFlag() {
        CliArgs args = CliArgs.parse(new String[]{"--new", "--no-memory"});
        assertTrue(args instanceof CliArgs.NewSession);
        assertTrue(args.noMemory());
    }

    @Test
    void noMemoryWithResume() {
        CliArgs args = CliArgs.parse(new String[]{"--resume", "abc", "--no-memory"});
        assertTrue(args instanceof CliArgs.Resume r
                && r.sessionId().equals("abc")
                && r.noMemory());
    }

    @Test
    void bypassPermissionsFlag() {
        CliArgs args = CliArgs.parse(new String[]{"--dangerously-bypass-permissions"});
        assertTrue(args instanceof CliArgs.Interactive i && i.dangerouslyBypassPermissions());
    }

    @Test
    void bypassPermissionsDefault() {
        CliArgs args = CliArgs.parse(new String[]{"--new"});
        assertTrue(args instanceof CliArgs.NewSession n && !n.dangerouslyBypassPermissions());
    }

    @Test
    void bypassPermissionsWithNewSession() {
        CliArgs args = CliArgs.parse(new String[]{"--new", "--dangerously-bypass-permissions"});
        assertTrue(args instanceof CliArgs.NewSession n && n.dangerouslyBypassPermissions());
    }

    @Test
    void bypassPermissionsWithContinue() {
        CliArgs args = CliArgs.parse(new String[]{"-c", "--dangerously-bypass-permissions"});
        assertTrue(args instanceof CliArgs.Continue c && c.dangerouslyBypassPermissions());
    }

    @Test
    void bypassPermissionsWithResume() {
        CliArgs args = CliArgs.parse(new String[]{"--resume", "abc", "--dangerously-bypass-permissions"});
        assertTrue(args instanceof CliArgs.Resume r
                && r.sessionId().equals("abc")
                && r.dangerouslyBypassPermissions());
    }

    @Test
    void bypassPermissionsWithNoMemory() {
        CliArgs args = CliArgs.parse(new String[]{"--new", "--no-memory", "--dangerously-bypass-permissions"});
        assertTrue(args instanceof CliArgs.NewSession n
                && n.noMemory()
                && n.dangerouslyBypassPermissions());
    }

    @Test
    void providerOverrideParsedFromSpaceSyntax() {
        CliArgs args = CliArgs.parse(new String[]{"--provider", "deepseek"});
        assertTrue(args instanceof CliArgs.Interactive i
                && "deepseek".equals(i.providerOverride()));
    }

    @Test
    void providerOverrideParsedFromEqualsSyntax() {
        CliArgs args = CliArgs.parse(new String[]{"--provider=deepseek", "--new"});
        assertTrue(args instanceof CliArgs.NewSession n
                && "deepseek".equals(n.providerOverride()));
    }

    @Test
    void providerOverrideMissingValueAtEndOfArgs() {
        assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(new String[]{"--provider"}));
    }

    @Test
    void providerOverrideMustNotSwallowNextFlag() {
        // Regression: previously `--provider --no-memory` set providerOverride="--no-memory"
        // and silently dropped --no-memory. Now it must error out.
        assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(new String[]{"--provider", "--no-memory"}));
    }

    @Test
    void providerOverrideEqualsFormRejectsEmptyValue() {
        assertThrows(IllegalArgumentException.class,
                () -> CliArgs.parse(new String[]{"--provider="}));
    }
}
