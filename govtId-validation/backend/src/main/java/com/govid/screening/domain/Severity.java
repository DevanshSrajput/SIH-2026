package com.govid.screening.domain;

/**
 * Severity of a single risk flag. {@code weight} is the number of risk points the
 * flag contributes before per-rule multipliers are applied.
 */
public enum Severity {
    INFO(0),
    LOW(6),
    MEDIUM(15),
    HIGH(32),
    CRITICAL(70);

    private final int weight;

    Severity(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }
}
