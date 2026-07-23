/** Semver range matcher. See SPEC.md for the full rules. */
public final class SemverRange {

    /**
     * Returns whether {@code version} (always MAJOR.MINOR.PATCH) satisfies
     * {@code range}. Throws IllegalArgumentException on malformed input.
     */
    public static boolean matches(String range, String version) {
        throw new UnsupportedOperationException("not implemented");
    }
}
