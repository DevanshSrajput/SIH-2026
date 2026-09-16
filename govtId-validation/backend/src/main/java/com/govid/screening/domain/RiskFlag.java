package com.govid.screening.domain;

import java.util.Map;

/**
 * A single finding raised by one of the screening modules.
 *
 * @param code     stable machine-readable identifier, e.g. {@code MRZ_CHECKDIGIT_MISMATCH}
 * @param module   module that raised the finding
 * @param severity how strongly this finding points at fraud
 * @param message  officer-facing explanation
 * @param evidence supporting values so a finding can be audited and re-checked later
 */
public record RiskFlag(
        String code,
        ScreeningModule module,
        Severity severity,
        String message,
        Map<String, Object> evidence) {

    public static RiskFlag of(String code, ScreeningModule module, Severity severity, String message) {
        return new RiskFlag(code, module, severity, message, Map.of());
    }

    public static RiskFlag of(String code, ScreeningModule module, Severity severity, String message,
                              Map<String, Object> evidence) {
        return new RiskFlag(code, module, severity, message, evidence == null ? Map.of() : evidence);
    }
}
