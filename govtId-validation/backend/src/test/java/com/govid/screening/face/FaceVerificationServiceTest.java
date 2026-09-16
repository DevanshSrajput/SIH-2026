package com.govid.screening.face;

import com.govid.screening.domain.ModuleResult;
import com.govid.screening.domain.ScreeningModule;
import com.govid.screening.domain.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for the face verification service orchestration.
 *
 * <p>These tests verify the service's decision logic (thresholds, flag generation)
 * without requiring actual face images or OpenCV models. The real face comparison
 * is tested in integration tests with actual images.
 */
class FaceVerificationServiceTest {

    private static final double MATCH_THRESHOLD = 0.75;
    private static final double MISMATCH_THRESHOLD = 0.55;

    @Test
    @DisplayName("skips when no live capture is provided")
    void skipsWithoutLiveCapture() {
        FaceVerificationService service = new FaceVerificationService(
                List.of(), MATCH_THRESHOLD, MISMATCH_THRESHOLD);

        var result = service.verify(new byte[]{1, 2, 3}, null);

        assertThat(result.status()).isEqualTo(ModuleResult.Status.SKIPPED);
        assertThat(result.module()).isEqualTo(ScreeningModule.FACE_VERIFICATION);
        assertThat(result.flags()).isEmpty();
    }

    @Test
    @DisplayName("skips when no face verifier is available")
    void skipsWithoutVerifiers() {
        FaceVerificationService service = new FaceVerificationService(
                List.of(), MATCH_THRESHOLD, MISMATCH_THRESHOLD);

        var result = service.verify(new byte[]{1, 2, 3}, new byte[]{4, 5, 6});

        assertThat(result.status()).isEqualTo(ModuleResult.Status.SKIPPED);
        assertThat(result.flags()).isEmpty();
    }

    @Test
    @DisplayName("uses first available verifier")
    void usesFirstAvailableVerifier() {
        // Create a mock verifier that returns a fixed result
        FaceVerifier mockVerifier = new FaceVerifier() {
            @Override
            public String name() { return "mock-verifier"; }

            @Override
            public boolean isAvailable() { return true; }

            @Override
            public FaceMatchResult compare(byte[] doc, byte[] live) {
                return new FaceMatchResult(
                        0.85, true, true, "mock-verifier", java.util.Map.of());
            }
        };

        FaceVerificationService service = new FaceVerificationService(
                List.of(mockVerifier), MATCH_THRESHOLD, MISMATCH_THRESHOLD);

        var result = service.verify(new byte[]{1, 2, 3}, new byte[]{4, 5, 6});

        assertThat(result.status()).isEqualTo(ModuleResult.Status.COMPLETED);
        assertThat(result.flags()).isEmpty(); // 0.85 > 0.75, no flags
    }

    @Test
    @DisplayName("generates FACE_MISMATCH flag when similarity is below mismatch threshold")
    void flagsMismatch() {
        FaceVerifier mockVerifier = new FaceVerifier() {
            @Override
            public String name() { return "mock-verifier"; }

            @Override
            public boolean isAvailable() { return true; }

            @Override
            public FaceMatchResult compare(byte[] doc, byte[] live) {
                return new FaceMatchResult(
                        0.3, true, true, "mock-verifier", java.util.Map.of());
            }
        };

        FaceVerificationService service = new FaceVerificationService(
                List.of(mockVerifier), MATCH_THRESHOLD, MISMATCH_THRESHOLD);

        var result = service.verify(new byte[]{1, 2, 3}, new byte[]{4, 5, 6});

        assertThat(result.flags()).hasSize(1);
        assertThat(result.flags().get(0).code()).isEqualTo("FACE_MISMATCH");
        assertThat(result.flags().get(0).severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    @DisplayName("generates FACE_INCONCLUSIVE flag when similarity is between thresholds")
    void flagsInconclusive() {
        FaceVerifier mockVerifier = new FaceVerifier() {
            @Override
            public String name() { return "mock-verifier"; }

            @Override
            public boolean isAvailable() { return true; }

            @Override
            public FaceMatchResult compare(byte[] doc, byte[] live) {
                return new FaceMatchResult(
                        0.65, true, true, "mock-verifier", java.util.Map.of());
            }
        };

        FaceVerificationService service = new FaceVerificationService(
                List.of(mockVerifier), MATCH_THRESHOLD, MISMATCH_THRESHOLD);

        var result = service.verify(new byte[]{1, 2, 3}, new byte[]{4, 5, 6});

        assertThat(result.flags()).hasSize(1);
        assertThat(result.flags().get(0).code()).isEqualTo("FACE_INCONCLUSIVE");
        assertThat(result.flags().get(0).severity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    @DisplayName("generates FACE_NOT_FOUND_ON_DOCUMENT when document face not detected")
    void flagsDocumentFaceNotFound() {
        FaceVerifier mockVerifier = new FaceVerifier() {
            @Override
            public String name() { return "mock-verifier"; }

            @Override
            public boolean isAvailable() { return true; }

            @Override
            public FaceMatchResult compare(byte[] doc, byte[] live) {
                return new FaceMatchResult(
                        0.0, false, true, "mock-verifier", java.util.Map.of());
            }
        };

        FaceVerificationService service = new FaceVerificationService(
                List.of(mockVerifier), MATCH_THRESHOLD, MISMATCH_THRESHOLD);

        var result = service.verify(new byte[]{1, 2, 3}, new byte[]{4, 5, 6});

        assertThat(result.flags()).hasSize(1);
        assertThat(result.flags().get(0).code()).isEqualTo("FACE_NOT_FOUND_ON_DOCUMENT");
        assertThat(result.flags().get(0).severity()).isEqualTo(Severity.HIGH);
    }

    @Test
    @DisplayName("generates FACE_NOT_FOUND_IN_CAPTURE when live face not detected")
    void flagsLiveFaceNotFound() {
        FaceVerifier mockVerifier = new FaceVerifier() {
            @Override
            public String name() { return "mock-verifier"; }

            @Override
            public boolean isAvailable() { return true; }

            @Override
            public FaceMatchResult compare(byte[] doc, byte[] live) {
                return new FaceMatchResult(
                        0.0, true, false, "mock-verifier", java.util.Map.of());
            }
        };

        FaceVerificationService service = new FaceVerificationService(
                List.of(mockVerifier), MATCH_THRESHOLD, MISMATCH_THRESHOLD);

        var result = service.verify(new byte[]{1, 2, 3}, new byte[]{4, 5, 6});

        assertThat(result.flags()).hasSize(1);
        assertThat(result.flags().get(0).code()).isEqualTo("FACE_NOT_FOUND_IN_CAPTURE");
        assertThat(result.flags().get(0).severity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    @DisplayName("handles verifier exception gracefully")
    void handlesVerifierException() {
        FaceVerifier brokenVerifier = new FaceVerifier() {
            @Override
            public String name() { return "broken-verifier"; }

            @Override
            public boolean isAvailable() { return true; }

            @Override
            public FaceMatchResult compare(byte[] doc, byte[] live) throws Exception {
                throw new RuntimeException("Model not loaded");
            }
        };

        FaceVerificationService service = new FaceVerificationService(
                List.of(brokenVerifier), MATCH_THRESHOLD, MISMATCH_THRESHOLD);

        var result = service.verify(new byte[]{1, 2, 3}, new byte[]{4, 5, 6});

        assertThat(result.status()).isEqualTo(ModuleResult.Status.FAILED);
        assertThat(result.flags()).isEmpty();
        assertThat(result.note()).contains("broken-verifier");
    }
}
