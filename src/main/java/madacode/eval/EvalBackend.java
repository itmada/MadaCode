package madacode.eval;

enum EvalBackend {
    LOCAL,
    DOCKER;

    static EvalBackend parse(String value) {
        if (value == null || value.isBlank() || "local".equalsIgnoreCase(value)) {
            return LOCAL;
        }
        if ("docker".equalsIgnoreCase(value)) {
            return DOCKER;
        }
        throw new IllegalArgumentException("--backend must be either local or docker");
    }
}
