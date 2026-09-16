package com.govid.screening.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Result of a face authentication comparison.
 *
 * <p>The decision is the field to read, not the similarity. A three-way answer -
 * MATCH, NO_MATCH or UNCERTAIN - is what the screening logic actually produces, and
 * collapsing it to a number and a threshold at the console would lose the part that
 * matters: whether the comparison could be trusted at all.
 *
 * <p>An UNCERTAIN result is not a failure. It means the system has measured what it can
 * and the measurement does not settle the question - because the score is ambiguous, or
 * because the capture was blurred, badly lit, angled away, spoofed, or had more than one
 * person in it. {@code blockers} says which, and {@code recaptureAdvice} says what to do.
 */
@Schema(name = "FaceAuthResult",
        description = "Result of comparing a document portrait against a live capture.")
public record FaceAuthResult(
        @Schema(description = "Raw cosine similarity in [0, 1]. Not a percentage: it is "
                + "compared against calibrated thresholds, not read as a confidence.",
                example = "0.58")
        double similarity,

        @Schema(description = "MATCH, NO_MATCH, or UNCERTAIN.", example = "MATCH")
        String decision,

        @Schema(description = "How much weight the decision deserves, in [0, 1]. A high "
                + "similarity with a low confidence is the case an officer most needs to see.",
                example = "0.71")
        double confidence,

        @Schema(description = "One sentence an officer can read off the screen.",
                example = "The traveller matches the portrait on the document (similarity 0.58).")
        String summary,

        @Schema(description = "What drove the decision.")
        List<String> reasons,

        @Schema(description = "Why a positive identification could not be issued, if it "
                + "could not. Empty on a clean comparison.")
        List<String> blockers,

        @Schema(description = "What to do about an uncertain result.",
                example = "Hold still and re-capture; the face is blurred.")
        String recaptureAdvice,

        @Schema(description = "Whether a face was found on the document image.",
                example = "true")
        boolean documentFaceFound,

        @Schema(description = "Whether a face was found in the live capture.",
                example = "true")
        boolean liveFaceFound,

        @Schema(description = "The matching engine that produced this result.",
                example = "http-face-service")
        String engine,

        @Schema(description = "Liveness score in [0, 1]. Higher = more likely a live person.",
                example = "0.87")
        double livenessScore,

        @Schema(description = "Whether the live capture passed the anti-spoofing check.",
                example = "true")
        boolean livenessLive,

        @Schema(description = "Image quality verdict for each side of the comparison.")
        Object quality,

        @Schema(description = "The thresholds this comparison was judged against.")
        Object thresholds,

        @Schema(description = "Engine-specific diagnostics for the audit trail.")
        Object details) {

    /**
     * Legacy alias for {@link #decision()}.
     *
     * <p>Older console builds read {@code recommendation} with the values MATCH /
     * MISMATCH / INCONCLUSIVE. Kept so an un-upgraded client does not silently read a
     * null and render "no result" over a genuine mismatch.
     */
    @Schema(description = "Deprecated alias for `decision`, using the older MATCH / "
            + "MISMATCH / INCONCLUSIVE vocabulary.", example = "MATCH")
    public String recommendation() {
        return switch (decision == null ? "" : decision) {
            case "MATCH" -> "MATCH";
            case "NO_MATCH" -> "MISMATCH";
            default -> "INCONCLUSIVE";
        };
    }
}
