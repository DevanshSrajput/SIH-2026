package com.govid.screening.domain;

import java.util.List;

/**
 * Aggregate decision produced by the risk engine.
 *
 * @param score       0-100, higher is riskier
 * @param band        LOW / MEDIUM / HIGH / CRITICAL
 * @param verdict     recommended officer action
 * @param flags       every finding from every module, ordered most severe first
 * @param topReasons  the handful of findings that actually drove the score
 * @param explanation one-paragraph rationale shown on the officer console
 */
public record RiskAssessment(
        int score,
        String band,
        Verdict verdict,
        List<RiskFlag> flags,
        List<String> topReasons,
        String explanation) {
}
