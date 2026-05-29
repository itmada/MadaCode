package madacode.tool;

import java.util.List;

public record ValidationResult(boolean valid, List<String> errors) {

    private static final ValidationResult OK = new ValidationResult(true, List.of());

    public ValidationResult {
        errors = List.copyOf(errors);
    }

    public static ValidationResult ok() {
        return OK;
    }

    public static ValidationResult invalid(List<String> errors) {
        return new ValidationResult(false, errors);
    }
}
