package madacode.tool;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

final class NetworkAccessPolicy {

    private static final Set<String> ALLOWED_PROTOCOLS = Set.of("http", "https");

    @FunctionalInterface
    interface Resolver {
        InetAddress[] resolve(String hostname) throws Exception;
    }

    private final boolean enforceNetworkRestrictions;
    private final Resolver resolver;

    NetworkAccessPolicy(boolean enforceNetworkRestrictions) {
        this(enforceNetworkRestrictions, InetAddress::getAllByName);
    }

    NetworkAccessPolicy(boolean enforceNetworkRestrictions, Resolver resolver) {
        this.enforceNetworkRestrictions = enforceNetworkRestrictions;
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    ValidationResult validate(String rawUrl) {
        URI parsed;
        try {
            parsed = URI.create(rawUrl);
        } catch (IllegalArgumentException e) {
            return ValidationResult.invalid("Invalid URL: " + rawUrl);
        }
        return validate(parsed);
    }

    ValidationResult validate(URI uri) {
        if (uri == null || !uri.isAbsolute()) {
            return ValidationResult.invalid("Invalid URL: " + uri);
        }

        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!ALLOWED_PROTOCOLS.contains(scheme)) {
            return ValidationResult.invalid(
                    "Unsupported protocol: " + scheme + ". Only http and https are allowed.");
        }
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            return ValidationResult.invalid("URLs with credentials are not allowed");
        }

        String hostname = uri.getHost();
        if (hostname == null || hostname.isBlank()) {
            return ValidationResult.invalid("URL has no valid hostname");
        }
        if (!hostname.contains(".") && !"localhost".equalsIgnoreCase(hostname)) {
            return ValidationResult.invalid(
                    "Cannot fetch from hostname: " + hostname + ". A fully qualified domain is required.");
        }

        URI upgraded = maybeUpgradeToHttps(uri);
        if (!enforceNetworkRestrictions) {
            return ValidationResult.valid(upgraded);
        }

        try {
            for (InetAddress address : resolver.resolve(upgraded.getHost())) {
                if (isForbiddenAddress(address)) {
                    return ValidationResult.invalid(
                            "Fetching from resolved address " + address.getHostAddress() + " is not allowed");
                }
            }
        } catch (Exception e) {
            return ValidationResult.invalid("Failed to resolve hostname " + upgraded.getHost() + ": " + e.getMessage());
        }

        return ValidationResult.valid(upgraded);
    }

    private URI maybeUpgradeToHttps(URI uri) {
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            return uri;
        }
        if (looksPrivateOrLoopbackHost(uri.getHost())) {
            return uri;
        }
        try {
            return new URI(
                    "https",
                    uri.getUserInfo(),
                    uri.getHost(),
                    uri.getPort(),
                    uri.getPath(),
                    uri.getQuery(),
                    uri.getFragment());
        } catch (URISyntaxException e) {
            return uri;
        }
    }

    private boolean looksPrivateOrLoopbackHost(String hostname) {
        if (hostname == null) {
            return false;
        }
        String lower = hostname.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.equals("127.0.0.1") || lower.equals("::1")) {
            return true;
        }
        return lower.matches("^10\\..+")
                || lower.matches("^172\\.(1[6-9]|2\\d|3[01])\\..+")
                || lower.matches("^192\\.168\\..+");
    }

    private boolean isForbiddenAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        if (address instanceof Inet6Address ipv6) {
            byte first = ipv6.getAddress()[0];
            return (first & (byte) 0xfe) == (byte) 0xfc;
        }
        return false;
    }

    record ValidationResult(URI uri, String error) {

        static ValidationResult valid(URI uri) {
            return new ValidationResult(uri, null);
        }

        static ValidationResult invalid(String error) {
            return new ValidationResult(null, error);
        }

        boolean isValid() {
            return uri != null;
        }
    }
}
