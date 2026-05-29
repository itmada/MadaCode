package madacode.tool;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkAccessPolicyTest {

    @Test
    void rejectsPublicHostnameThatResolvesToLoopback() throws Exception {
        NetworkAccessPolicy policy = new NetworkAccessPolicy(
                true,
                hostname -> new InetAddress[] { InetAddress.getByName("127.0.0.1") });

        NetworkAccessPolicy.ValidationResult result = policy.validate("http://example.com/data");

        assertFalse(result.isValid());
        assertTrue(result.error().contains("resolved address"));
    }

    @Test
    void rejectsForbiddenRedirectTarget() throws Exception {
        NetworkAccessPolicy policy = new NetworkAccessPolicy(
                true,
                hostname -> new InetAddress[] { InetAddress.getByName("127.0.0.1") });

        NetworkAccessPolicy.ValidationResult result = policy.validate(URI.create("https://redirect.example/target"));

        assertFalse(result.isValid());
        assertTrue(result.error().contains("not allowed"));
    }
}
