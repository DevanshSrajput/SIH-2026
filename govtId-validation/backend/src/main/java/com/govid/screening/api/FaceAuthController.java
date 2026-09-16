package com.govid.screening.api;

import com.govid.screening.api.dto.FaceAuthResult;
import com.govid.screening.face.FaceVerificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Dedicated face authentication endpoint.
 *
 * <p>Provides a standalone face comparison endpoint separate from the full screening
 * pipeline. Useful for:
 * <ul>
 *   <li>Quick identity verification without running the full document pipeline</li>
 *   <li>Re-verification when the screening result was inconclusive</li>
 *   <li>Enrollment: capturing a reference portrait for future comparisons</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/face")
@Tag(name = "Face Authentication",
        description = "Compare a document portrait against a live capture, with liveness detection.")
public class FaceAuthController {

    private final FaceVerificationService faceVerificationService;

    public FaceAuthController(FaceVerificationService faceVerificationService) {
        this.faceVerificationService = faceVerificationService;
    }

    /**
     * Compares a document portrait against a live capture.
     *
     * <p>Both images are required. The document image should contain the portrait photo
     * from the identity document; the live capture should be a camera image of the person
     * presenting it.
     */
    @Operation(summary = "Compare a document portrait against a live capture",
            description = """
                    Runs face detection, liveness checking, and embedding-based comparison.
                    Returns a similarity score and a recommendation (MATCH / MISMATCH / INCONCLUSIVE).

                    This endpoint is for quick identity checks. For a full document screening
                    with tampering detection and watchlist checks, use POST /api/screenings.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Comparison completed."),
            @ApiResponse(responseCode = "400", description = "Missing or unreadable image.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(type = "string"))),
            @ApiResponse(responseCode = "503", description = "No face matcher is available.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(type = "string")))})
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public FaceAuthResult compare(
            @Parameter(description = "Document image containing the portrait photo.",
                    required = true)
            @RequestPart("document") MultipartFile document,

            @Parameter(description = "Live camera capture of the person.",
                    required = true)
            @RequestPart("live") MultipartFile live) throws IOException {

        if (document == null || document.isEmpty()) {
            throw new IllegalArgumentException("A document image is required.");
        }
        if (live == null || live.isEmpty()) {
            throw new IllegalArgumentException("A live capture image is required.");
        }

        // Delegate to the existing face verification service which selects the best
        // available verifier (HTTP service or local OpenCV).
        com.govid.screening.domain.ModuleResult result = faceVerificationService.verify(
                document.getBytes(), live.getBytes());

        // Extract the face match details from the module result
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> details = result.details();

        double similarity = 0.0;
        boolean docFaceFound = false;
        boolean liveFaceFound = false;
        String engine = "unknown";
        double livenessScore = 0.0;
        boolean livenessLive = false;

        if (details.containsKey("similarity")) {
            similarity = ((Number) details.get("similarity")).doubleValue();
        }
        if (details.containsKey("engine")) {
            engine = String.valueOf(details.get("engine"));
        }
        if (details.containsKey("livenessScore")) {
            livenessScore = ((Number) details.get("livenessScore")).doubleValue();
        }
        if (details.containsKey("livenessLive")) {
            livenessLive = (Boolean) details.get("livenessLive");
        }

        // Check flags for face detection info
        for (com.govid.screening.domain.RiskFlag flag : result.flags()) {
            switch (flag.code()) {
                case "FACE_NOT_FOUND_ON_DOCUMENT" -> docFaceFound = false;
                case "FACE_NOT_FOUND_IN_CAPTURE" -> liveFaceFound = false;
                default -> {}
            }
        }

        // If no flags say faces were not found, they were found
        docFaceFound = result.flags().stream()
                .noneMatch(f -> "FACE_NOT_FOUND_ON_DOCUMENT".equals(f.code()));
        liveFaceFound = result.flags().stream()
                .noneMatch(f -> "FACE_NOT_FOUND_IN_CAPTURE".equals(f.code()));

        // Determine recommendation
        String recommendation = determineRecommendation(
                similarity, docFaceFound, liveFaceFound, livenessLive);

        return new FaceAuthResult(
                similarity,
                docFaceFound,
                liveFaceFound,
                engine,
                livenessScore,
                livenessLive,
                recommendation,
                details);
    }

    private String determineRecommendation(double similarity, boolean docFaceFound,
                                           boolean liveFaceFound, boolean livenessLive) {
        if (!docFaceFound || !liveFaceFound) {
            return "INCONCLUSIVE";
        }
        if (!livenessLive) {
            return "INCONCLUSIVE";
        }
        // Use the same thresholds as the screening pipeline
        if (similarity >= 0.75) {
            return "MATCH";
        }
        if (similarity < 0.55) {
            return "MISMATCH";
        }
        return "INCONCLUSIVE";
    }
}
