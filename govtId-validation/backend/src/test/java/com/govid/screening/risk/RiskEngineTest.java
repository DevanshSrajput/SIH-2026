package com.govid.screening.risk;

import com.govid.screening.domain.ModuleResult;
import com.govid.screening.domain.RiskAssessment;
import com.govid.screening.domain.RiskFlag;
import com.govid.screening.domain.ScreeningModule;
import com.govid.screening.domain.Severity;
import com.govid.screening.domain.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RiskEngineTest {

    private final RiskEngine engine = new RiskEngine(70, 35);

    private static ModuleResult moduleWith(RiskFlag... flags) {
        return new ModuleResult(ScreeningModule.DOCUMENT_VALIDATION, ModuleResult.Status.COMPLETED,
                1L, List.of(flags), Map.of(), null);
    }

    private static RiskFlag flag(Severity severity) {
        return RiskFlag.of("TEST_" + severity, ScreeningModule.DOCUMENT_VALIDATION, severity,
                "finding of severity " + severity);
    }

    @Test
    @DisplayName("clears a document with no findings")
    void clearsWithNoFindings() {
        RiskAssessment assessment = engine.assess(List.of(moduleWith()));

        assertThat(assessment.score()).isZero();
        assertThat(assessment.verdict()).isEqualTo(Verdict.CLEAR);
        assertThat(assessment.band()).isEqualTo("LOW");
    }

    @Test
    @DisplayName("one critical finding is enough to recommend rejection")
    void singleCriticalRejects() {
        RiskAssessment assessment = engine.assess(List.of(moduleWith(flag(Severity.CRITICAL))));

        assertThat(assessment.score()).isEqualTo(70);
        assertThat(assessment.verdict()).isEqualTo(Verdict.REJECT);
    }

    @Test
    @DisplayName("accumulated minor findings never outweigh one decisive finding")
    void minorFindingsSaturate() {
        List<RiskFlag> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(flag(Severity.LOW));
        }

        int manyMinor = engine.assess(List.of(moduleWith(many.toArray(new RiskFlag[0])))).score();
        int oneCritical = engine.assess(List.of(moduleWith(flag(Severity.CRITICAL)))).score();

        assertThat(manyMinor).isLessThan(oneCritical);
    }

    @Test
    @DisplayName("the score never exceeds 100 however many findings arrive")
    void scoreIsBounded() {
        List<RiskFlag> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(flag(Severity.CRITICAL));
        }

        assertThat(engine.assess(List.of(moduleWith(many.toArray(new RiskFlag[0])))).score())
                .isBetween(0, 100);
    }

    @Test
    @DisplayName("adding evidence never lowers the score")
    void scoreIsMonotonic() {
        int one = engine.assess(List.of(moduleWith(flag(Severity.MEDIUM)))).score();
        int two = engine.assess(List.of(moduleWith(flag(Severity.MEDIUM), flag(Severity.LOW)))).score();

        assertThat(two).isGreaterThanOrEqualTo(one);
    }

    @Test
    @DisplayName("the verdict does not depend on the order modules ran in")
    void verdictIsOrderIndependent() {
        List<ModuleResult> results = new ArrayList<>(List.of(
                moduleWith(flag(Severity.HIGH)),
                moduleWith(flag(Severity.MEDIUM)),
                moduleWith(flag(Severity.LOW))));

        int first = engine.assess(results).score();
        Collections.reverse(results);
        int reversed = engine.assess(results).score();

        assertThat(reversed).isEqualTo(first);
    }

    @Test
    @DisplayName("refuses to clear a case when a module failed to run")
    void failedModuleBlocksClear() {
        List<ModuleResult> results = List.of(
                moduleWith(),
                ModuleResult.failed(ScreeningModule.OCR_EXTRACTION, 5L, "no engine available"));

        RiskAssessment assessment = engine.assess(results);

        assertThat(assessment.score()).isZero();
        assertThat(assessment.verdict()).isEqualTo(Verdict.REVIEW);
        assertThat(assessment.explanation()).contains("partial evidence");
    }

    @Test
    @DisplayName("a skipped optional module does not by itself force a review")
    void skippedModuleDoesNotBlockClear() {
        List<ModuleResult> results = List.of(
                moduleWith(),
                ModuleResult.skipped(ScreeningModule.FACE_VERIFICATION, "no live capture supplied"));

        assertThat(engine.assess(results).verdict()).isEqualTo(Verdict.CLEAR);
    }

    @Test
    @DisplayName("orders findings most severe first and explains the top contributor")
    void ordersFindingsBySeverity() {
        RiskAssessment assessment = engine.assess(List.of(
                moduleWith(flag(Severity.LOW)),
                moduleWith(flag(Severity.CRITICAL)),
                moduleWith(flag(Severity.MEDIUM))));

        assertThat(assessment.flags().get(0).severity()).isEqualTo(Severity.CRITICAL);
        assertThat(assessment.topReasons()).isNotEmpty();
        assertThat(assessment.explanation()).contains("CRITICAL");
    }

    @Test
    @DisplayName("maps scores onto review and reject bands at the configured thresholds")
    void appliesConfiguredThresholds() {
        // MEDIUM(15) + HIGH(32) -> 1 - 0.85*0.68 = 0.422 -> 42, inside the review band.
        RiskAssessment review = engine.assess(
                List.of(moduleWith(flag(Severity.MEDIUM), flag(Severity.HIGH))));

        assertThat(review.score()).isEqualTo(42);
        assertThat(review.verdict()).isEqualTo(Verdict.REVIEW);
        assertThat(review.band()).isEqualTo("MEDIUM");
    }
}
