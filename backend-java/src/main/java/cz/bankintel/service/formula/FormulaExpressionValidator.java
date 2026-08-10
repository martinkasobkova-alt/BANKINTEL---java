package cz.bankintel.service.formula;

import java.util.regex.Pattern;

public final class FormulaExpressionValidator {

    private static final Pattern ALLOWED = Pattern.compile("^[0-9a-zA-Z_.+\\-*/()\\s]+$");

    private FormulaExpressionValidator() {}

    public static ValidationResult validate(String expression) {
        if (expression == null || expression.isBlank()) {
            return new ValidationResult(false, "Expression is required");
        }
        String trimmed = expression.strip();
        if (!ALLOWED.matcher(trimmed).matches()) {
            return new ValidationResult(false, "Unsupported characters in expression");
        }
        return new ValidationResult(true, "ok");
    }

    public record ValidationResult(boolean ok, String message) {}
}
