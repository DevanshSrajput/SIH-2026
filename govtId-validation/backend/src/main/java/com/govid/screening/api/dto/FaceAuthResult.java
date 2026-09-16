package com.govid.screening.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Result of a face authentication comparison.
 *
 * <p>Returned by the face verification endpoint. The similarity score is in [0, 1] where
 * 1.0 means identical. Two thresholds are provided so the caller can implement a
 * three-band decision: accept (above match threshold), reject (below mismatch threshold),
 * or refer to an officer (between).
 */
@Schema(name = "FaceAuthResult",
        description = "Result of comparing a document portrait against a live capture.")
public record FaceAuthResult(
        @Schema(description = "Similarity score in [0, 1]. 1.0 = identical face.",
                example = "0.82")
        double similarity,

        @Schema(description = "Whether a face was found on the document image.",
                example = "true")
        boolean documentFaceFound,

        @Schema(description = "Whether a face was found in the live capture.",
                example = "true")
        boolean liveFaceFound,

        @Schema(description = "The matching engine that produced this result.",
                example = "local-opencv-sface")
        String engine,

        @Schema(description = "Liveness score in [0, 1]. Higher = more likely live.",
                example = "0.87")
        double livenessScore,

        @Schema(description = "Whether the live capture passed the liveness check.",
                example = "true")
        boolean livenessLive,

        @Schema(description = "Match recommendation: MATCH, MISMATCH, or INCONCLUSIVE.",
                example = "MATCH")
        String recommendation,

        @Schema(description = "Engine-specific diagnostics for the audit trail.")
        Object details) {
}
