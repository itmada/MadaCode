package madacode.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PermissionModeTest {

    @Test
    void parseAcceptsCanonicalIdsAndEvalAliases() {
        assertEquals(PermissionMode.DEFAULT, PermissionMode.parse("default").orElseThrow());
        assertEquals(PermissionMode.EDIT, PermissionMode.parse("edit").orElseThrow());
        assertEquals(PermissionMode.EDIT, PermissionMode.parse("accept-edits").orElseThrow());
        assertEquals(PermissionMode.BYPASS, PermissionMode.parse("all-pass").orElseThrow());
        assertEquals(PermissionMode.BYPASS, PermissionMode.parse("bypass").orElseThrow());
    }
}
