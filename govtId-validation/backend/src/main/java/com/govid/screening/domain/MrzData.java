package com.govid.screening.domain;

import java.util.List;
import java.util.Map;

/**
 * Result of parsing an ICAO 9303 Machine Readable Zone.
 *
 * @param format          TD1 (3x30), TD2 (2x36), TD3 (2x44) or MRV_A / MRV_B for visas
 * @param lines           the raw MRZ lines exactly as read
 * @param checkDigits     field name to {@code true} when the printed check digit matched
 * @param composite       whether the final composite check digit matched
 */
public record MrzData(
        String format,
        List<String> lines,
        Map<String, Boolean> checkDigits,
        Boolean composite) {

    /** Fields whose printed check digit did not match the recomputed value. */
    public List<String> failedCheckDigits() {
        return checkDigits.entrySet().stream()
                .filter(e -> Boolean.FALSE.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    public boolean allCheckDigitsValid() {
        return failedCheckDigits().isEmpty() && !Boolean.FALSE.equals(composite);
    }
}
