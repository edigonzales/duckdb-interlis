package ch.so.agi.duckdbili.core.validation;

public enum ValidationProfile {

    FULL("full"),
    STRUCTURAL("structural"),
    FAST("fast");

    private final String name;

    ValidationProfile(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static ValidationProfile fromString(String s) {
        if (s == null || s.isBlank()) {
            return FULL;
        }
        return switch (s.trim().toLowerCase()) {
            case "structural" -> STRUCTURAL;
            case "fast" -> FAST;
            default -> FULL;
        };
    }

    public boolean isConstraintValidationEnabled() {
        return this == FULL || this == STRUCTURAL;
    }

    public boolean isAreaValidationEnabled() {
        return this == FULL;
    }

    public boolean isAllObjectsAccessible() {
        return this == FULL || this == STRUCTURAL;
    }

    public boolean isMultiplicityValidationEnabled() {
        return this == FULL || this == STRUCTURAL;
    }
}
