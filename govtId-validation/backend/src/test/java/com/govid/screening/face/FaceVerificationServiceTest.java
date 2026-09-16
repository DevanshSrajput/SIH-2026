package com.govid.screening.face;

import com.govid.screening.domain.ModuleResult;
import com.govid.screening.domain.RiskFlag;
import com.govid.screening.domain.ScreeningModule;
import com.govid.screening.domain.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the face verification service orchestration.
 *
 * <p>These cover the service's decision logic - thresholds, deference to a matcher that
 * decided for itself, and flag generation - without needing face images or OpenCV models.
 * The comparison itself is tested in the face service's own suite.
 *
 * <p>The property asserted throughout is one-sided. The service is allowed to be wrong by
 * being unsure. It is not allowed to be wrong by being confident, so every route that could
 * produce a clean MATCH on evidence that does not support one is exercised here.
 */
class FaceVerificationServiceTest {

    /** Raw-cosine thresholds, matching the shipped configuration. */
    private static final double MATCH_THRESHOLD = 0.46;
    private static final double MISMATCH_THRESHOLD = 0.28;
    private static final double MARGIN = 0.03;

    private static FaceVerificationService serviceWith(FaceVerifier... verifiers) {
        return new FaceVerificationService(
                List.of(verifiers), MATCH_THRESHOLD, MISMATCH_THRESHOLD, MARGIN);
    }

    /** A matcher returning a fixed result. */
    private static FaceVerifier verifier(FaceVerifier.FaceMatchResult result) {
        return new FaceVerifier() {
            @Override
            public String name() { return "mock-verifier"; }

            @Override
            public boolean isAvailable() { return true; }

            @Override
            public FaceVerifier.FaceMatchResult compare(byte[] doc, byte[] live) { return result; }
        };
    }

    /** A score-only matcher, of the kind that leaves the decision to this service. */
    private static FaceVerifier scoring(double similarity) {
        return verifier(new FaceVerifier.FaceMatchResult(
                similarity, true, true, "mock-verifier", Map.of()));
    }

    private static ModuleResult run(FaceVerifier verifier) {
        return serviceWith(verifier).verify(new byte[]{1, 2, 3}, new byte[]{4, 5, 6});
    }

    private static List<String> codes(ModuleResult result) {
        return result.flags().stream().map(RiskFlag::code).toList();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("skips when no live capture is provided")
    void skipsWithoutLiveCapture() {
        var result = serviceWith().verify(new byte[]{1, 2, 3}, null);

        assertThat(result.status()).isEqualTo(ModuleResult.Status.SKIPPED);
        assertThat(result.module()).isEqualTo(ScreeningModule.FACE_VERIFICATION);
        assertThat(result.flags()).isEmpty();
    }

    @Test
    @DisplayName("skips when no face verifier is available")
    void skipsWithoutVerifiers() {
        var result = serviceWith().verify(new byte[]{1, 2, 3}, new byte[]{4, 5, 6});

        assertThat(result.status()).isEqualTo(ModuleResult.Status.SKIPPED);
        assertThat(result.flags()).isEmpty();
    }

    @Test
    @DisplayName("handles verifier exception gracefully")
    void handlesVerifierException() {
        FaceVerifier broken = new FaceVerifier() {
            @Override
            public String name() { return "broken-verifier"; }

            @Override
            public boolean isAvailable() { return true; }

            @Override
            public FaceMatchResult compare(byte[] doc, byte[] live) {
                throw new RuntimeException("Model not loaded");
            }
        };

        var result = run(broken);

        assertThat(result.status()).isEqualTo(ModuleResult.Status.FAILED);
        assertThat(result.flags()).isEmpty();
        assertThat(result.note()).contains("broken-verifier");
    }

    @Test
    @DisplayName("prefers the first available verifier")
    void skipsUnavailableVerifiers() {
        FaceVerifier unavailable = new FaceVerifier() {
            @Override
            public String name() { return "offline"; }

            @Override
            public boolean isAvailable() { return false; }

            @Override
            public FaceMatchResult compare(byte[] doc, byte[] live) {
                throw new AssertionError("an unavailable verifier must not be called");
            }
        };

        var result = serviceWith(unavailable, scoring(0.70))
                .verify(new byte[]{1, 2, 3}, new byte[]{4, 5, 6});

        assertThat(result.status()).isEqualTo(ModuleResult.Status.COMPLETED);
        assertThat(result.details()).containsEntry("decision", "MATCH");
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("threshold bands, applied to a score-only matcher")
    class Bands {

        @Test
        @DisplayName("a decisive score is a match and raises no flag")
        void matches() {
            var result = run(scoring(0.70));

            assertThat(result.status()).isEqualTo(ModuleResult.Status.COMPLETED);
            assertThat(result.details()).containsEntry("decision", "MATCH");
            assertThat(result.flags()).isEmpty();
        }

        @Test
        @DisplayName("a low score raises FACE_MISMATCH as critical")
        void flagsMismatch() {
            var result = run(scoring(0.05));

            assertThat(result.details()).containsEntry("decision", "NO_MATCH");
            assertThat(codes(result)).containsExactly("FACE_MISMATCH");
            assertThat(result.flags().get(0).severity()).isEqualTo(Severity.CRITICAL);
        }

        @Test
        @DisplayName("a score between the thresholds raises FACE_INCONCLUSIVE")
        void flagsInconclusive() {
            var result = run(scoring(0.37));

            assertThat(result.details()).containsEntry("decision", "UNCERTAIN");
            assertThat(codes(result)).containsExactly("FACE_INCONCLUSIVE");
            assertThat(result.flags().get(0).severity()).isEqualTo(Severity.MEDIUM);
        }

        @Test
        @DisplayName("a score sitting on the match threshold is inconclusive, not a match")
        void marginAboveMatchThresholdIsUncertain() {
            var result = run(scoring(MATCH_THRESHOLD + MARGIN / 2));

            assertThat(result.details()).containsEntry("decision", "UNCERTAIN");
            assertThat(codes(result)).contains("FACE_INCONCLUSIVE");
        }

        @Test
        @DisplayName("a score sitting on the mismatch threshold is inconclusive, not an accusation")
        void marginBelowMismatchThresholdIsUncertain() {
            var result = run(scoring(MISMATCH_THRESHOLD - MARGIN / 2));

            assertThat(result.details()).containsEntry("decision", "UNCERTAIN");
            assertThat(codes(result)).doesNotContain("FACE_MISMATCH");
        }

        @Test
        @DisplayName("SFace's own break-even point is not enough to assert a match")
        void publishedBreakEvenIsNotAMatch() {
            // 0.363 balances false accepts against false rejects. A checkpoint does not
            // want them balanced.
            var result = run(scoring(0.363));

            assertThat(result.details()).doesNotContainEntry("decision", "MATCH");
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("no route produces a false positive")
    class NoFalsePositives {

        @Test
        @DisplayName("a matcher that says UNCERTAIN is never upgraded to a match")
        void respectsMatcherUncertainty() {
            var result = run(verifier(new FaceVerifier.FaceMatchResult(
                    0.95, true, true, "mock-verifier", Map.of(),
                    FaceDecision.UNCERTAIN, 0.2,
                    List.of("Score is high but the capture is blurred"),
                    List.of("Live capture quality is insufficient"),
                    "The system cannot say whether this is the same person.",
                    "Hold still and re-capture.")));

            assertThat(result.details()).containsEntry("decision", "UNCERTAIN");
            assertThat(codes(result)).contains("FACE_INCONCLUSIVE");
            assertThat(result.details()).containsKey("recaptureAdvice");
        }

        @Test
        @DisplayName("an unaligned comparison cannot assert a match however high the score")
        void unalignedCropsCannotMatch() {
            var result = run(verifier(new FaceVerifier.FaceMatchResult(
                    0.92, true, true, "local-fallback",
                    Map.of("alignmentUsed", false))));

            assertThat(result.details()).containsEntry("decision", "UNCERTAIN");
            assertThat(codes(result)).contains("FACE_INCONCLUSIVE");
        }

        @Test
        @DisplayName("a failed liveness check is reported alongside the comparison")
        void flagsLivenessSeparately() {
            var result = run(verifier(new FaceVerifier.FaceMatchResult(
                    0.70, true, true, "mock-verifier",
                    Map.of("livenessLive", false, "livenessScore", 0.2),
                    FaceDecision.UNCERTAIN, 0.3, List.of(),
                    List.of("Liveness check failed"), "Possible spoof.", null)));

            assertThat(codes(result)).contains("FACE_LIVENESS_SUSPECT", "FACE_INCONCLUSIVE");
            assertThat(result.flags().stream()
                    .filter(f -> "FACE_LIVENESS_SUSPECT".equals(f.code()))
                    .findFirst().orElseThrow().severity()).isEqualTo(Severity.HIGH);
        }

        @Test
        @DisplayName("an unusable image raises FACE_QUALITY_INSUFFICIENT")
        void flagsPoorQuality() {
            var result = run(verifier(new FaceVerifier.FaceMatchResult(
                    0.70, true, true, "mock-verifier",
                    Map.of("quality", Map.of(
                            "document", Map.of("usable", true),
                            "live", Map.of("usable", false))),
                    FaceDecision.UNCERTAIN, 0.2, List.of(),
                    List.of("Live capture quality is insufficient"), null,
                    "Re-capture under better lighting.")));

            assertThat(codes(result)).contains("FACE_QUALITY_INSUFFICIENT");
        }

        @Test
        @DisplayName("a clean match carries no quality or liveness flag")
        void cleanMatchIsUnflagged() {
            var result = run(verifier(new FaceVerifier.FaceMatchResult(
                    0.70, true, true, "mock-verifier",
                    Map.of("livenessLive", true,
                            "alignmentUsed", true,
                            "quality", Map.of(
                                    "document", Map.of("usable", true),
                                    "live", Map.of("usable", true))),
                    FaceDecision.MATCH, 0.8, List.of(), List.of(), "Match.", null)));

            assertThat(result.flags()).isEmpty();
        }
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("face detection failures")
    class DetectionFailures {

        @Test
        @DisplayName("generates FACE_NOT_FOUND_ON_DOCUMENT when no portrait is located")
        void flagsDocumentFaceNotFound() {
            var result = run(verifier(new FaceVerifier.FaceMatchResult(
                    0.0, false, true, "mock-verifier", Map.of())));

            assertThat(codes(result)).containsExactly("FACE_NOT_FOUND_ON_DOCUMENT");
            assertThat(result.flags().get(0).severity()).isEqualTo(Severity.HIGH);
        }

        @Test
        @DisplayName("generates FACE_NOT_FOUND_IN_CAPTURE when no live face is located")
        void flagsLiveFaceNotFound() {
            var result = run(verifier(new FaceVerifier.FaceMatchResult(
                    0.0, true, false, "mock-verifier", Map.of())));

            assertThat(codes(result)).containsExactly("FACE_NOT_FOUND_IN_CAPTURE");
            assertThat(result.flags().get(0).severity()).isEqualTo(Severity.MEDIUM);
        }

        @Test
        @DisplayName("a missing face never produces a mismatch accusation")
        void missingFaceIsNotAMismatch() {
            var result = run(verifier(new FaceVerifier.FaceMatchResult(
                    0.0, false, false, "mock-verifier", Map.of())));

            assertThat(codes(result))
                    .containsExactlyInAnyOrder(
                            "FACE_NOT_FOUND_ON_DOCUMENT", "FACE_NOT_FOUND_IN_CAPTURE")
                    .doesNotContain("FACE_MISMATCH");
        }
    }
}
