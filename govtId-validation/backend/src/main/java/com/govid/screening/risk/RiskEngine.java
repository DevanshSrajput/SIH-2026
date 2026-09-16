package com.govid.screening.risk;

import com.govid.screening.domain.ModuleResult;
import com.govid.screening.domain.RiskAssessment;
import com.govid.screening.domain.RiskFlag;
import com.govid.screening.domain.Severity;
import com.govid.screening.domain.Verdict;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Turns the modules' findings into a single score and a recommended action.
 *
 * <p>Findings are combined the way independent evidence combines, not by adding points:
 *
 * <pre>score = 100 * (1 - Π(1 - weight_i))</pre>
 *
 * <p>Each finding reduces the remaining probability that the document is sound. Three
 * properties follow, and all three matter at a checkpoint:
 * <ul>
 *   <li><b>It saturates.</b> A pile of minor observations can never out-score a single
 *       decisive one, so a document cannot be rejected by accumulated trivia.</li>
 *   <li><b>It is monotonic.</b> More evidence never lowers the score, so a finding can
 *       never be cancelled out by an unrelated one.</li>
 *   <li><b>It is order-independent.</b> The modules can run in any order, or in parallel,
 *       and the verdict does not move.</li>
 * </ul>
 *
 * <p>The engine recommends; it does not decide. The officer's own determination is
 * recorded separately and is what governs.
 */
@Service
public class RiskEngine {

    /** At or above this score, the evidence of forgery or fraud is decisive. */
    private final int rejectThreshold;

    /** At or above this score, a human has to look before the traveller proceeds. */
    private final int reviewThreshold;

    public RiskEngine(
            @Value("${screening.risk.reject-threshold:70}") int rejectThreshold,
            @Value("${screening.risk.review-threshold:35}") int reviewThreshold) {
        this.rejectThreshold = rejectThreshold;
        this.reviewThreshold = reviewThreshold;
    }

    public RiskAssessment assess(List<ModuleResult> moduleResults) {
        List<RiskFlag> flags = moduleResults.stream()
                .flatMap(result -> result.flags().stream())
                .sorted(Comparator.comparingInt((RiskFlag flag) -> flag.severity().weight()).reversed())
                .toList();

        double remaining = 1.0;
        for (RiskFlag flag : flags) {
            remaining *= (1.0 - flag.severity().weight() / 100.0);
        }
        int score = (int) Math.round((1.0 - remaining) * 100);

        Verdict verdict = verdictFor(score);

        // A module that could not run is missing evidence, not absent evidence. The case
        // cannot be cleared on a partial examination, so it is referred to an officer.
        List<ModuleResult> incomplete = moduleResults.stream()
                .filter(result -> result.status() != ModuleResult.Status.COMPLETED)
                .toList();
        boolean coverageGap = incomplete.stream()
                .anyMatch(result -> result.status() == ModuleResult.Status.FAILED);
        if (coverageGap && verdict == Verdict.CLEAR) {
            verdict = Verdict.REVIEW;
        }

        List<String> topReasons = flags.stream()
                .filter(flag -> flag.severity().weight() > 0)
                .limit(5)
                .map(RiskFlag::message)
                .toList();

        return new RiskAssessment(
                score,
                band(score),
                verdict,
                flags,
                topReasons,
                explain(score, verdict, flags, incomplete));
    }

    private Verdict verdictFor(int score) {
        if (score >= rejectThreshold) {
            return Verdict.REJECT;
        }
        if (score >= reviewThreshold) {
            return Verdict.REVIEW;
        }
        return Verdict.CLEAR;
    }

    private static String band(int score) {
        if (score >= 70) {
            return "CRITICAL";
        }
        if (score >= 45) {
            return "HIGH";
        }
        if (score >= 20) {
            return "MEDIUM";
        }
        return "LOW";
    }

    /**
     * Writes the one-paragraph rationale shown on the officer console. It names what
     * drove the score, so the recommendation can be challenged rather than merely obeyed.
     */
    private static String explain(int score, Verdict verdict, List<RiskFlag> flags,
                                  List<ModuleResult> incomplete) {
        StringBuilder text = new StringBuilder();

        long critical = flags.stream().filter(f -> f.severity() == Severity.CRITICAL).count();
        long high = flags.stream().filter(f -> f.severity() == Severity.HIGH).count();

        if (flags.isEmpty()) {
            text.append("No findings were raised by any module. ");
        } else {
            text.append("Risk score ").append(score).append(" from ")
                    .append(flags.size()).append(" finding(s)");
            if (critical > 0 || high > 0) {
                text.append(" including ").append(critical).append(" critical and ")
                        .append(high).append(" high-severity");
            }
            text.append(". ");
            text.append("The largest single contributor is: ")
                    .append(flags.get(0).message()).append(" ");
        }

        if (!incomplete.isEmpty()) {
            String names = incomplete.stream()
                    .map(result -> result.module().label())
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            text.append("Note that ").append(names)
                    .append(" did not complete, so this assessment is based on partial evidence. ");
        }

        text.append(switch (verdict) {
            case CLEAR -> "Recommendation: no obstacle to entry on document grounds.";
            case REVIEW -> "Recommendation: refer to an officer for manual inspection before deciding.";
            case REJECT -> "Recommendation: do not accept this document without supervisory review.";
        });

        return text.toString();
    }
}
