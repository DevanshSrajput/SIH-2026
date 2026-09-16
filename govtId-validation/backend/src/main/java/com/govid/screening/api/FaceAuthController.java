package com.govid.screening.api;

import com.govid.screening.api.dto.ApiError;
import com.govid.screening.api.dto.FaceAuthResult;
import com.govid.screening.domain.FaceEnrolment;
import com.govid.screening.domain.ModuleResult;
import com.govid.screening.domain.RiskFlag;
import com.govid.screening.face.FaceEnrolmentService;
import com.govid.screening.face.FaceVerificationService;
import com.govid.screening.repository.FaceEnrolmentRepository;
import com.govid.screening.support.Uploads;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Face authentication: 1:1 verification, enrolment, and 1:N identification.
 *
 * <p>Separate from the full screening pipeline, for the checks that do not need it:
 * <ul>
 *   <li>a quick identity check without running document forensics</li>
 *   <li>re-verification after an inconclusive screening result</li>
 *   <li>enrolling a known traveller so a repeat crossing is fast</li>
 *   <li>asking who a person is, rather than whether they are who they claim</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/face")
@Tag(name = "Face Authentication",
        description = "Compare a document portrait against a live capture, enrol known "
                + "travellers, and identify a face against the enrolled gallery.")
public class FaceAuthController {

    private final FaceVerificationService faceVerificationService;
    private final FaceEnrolmentService enrolmentService;
    private final FaceEnrolmentRepository enrolmentRepository;

    public FaceAuthController(FaceVerificationService faceVerificationService,
                              FaceEnrolmentService enrolmentService,
                              FaceEnrolmentRepository enrolmentRepository) {
        this.faceVerificationService = faceVerificationService;
        this.enrolmentService = enrolmentService;
        this.enrolmentRepository = enrolmentRepository;
    }

    // ------------------------------------------------------------------
    // 1:1 verification
    // ------------------------------------------------------------------

    @Operation(summary = "Compare a document portrait against a live capture",
            description = """
                    Runs face detection, image-quality assessment, liveness checking and \
                    embedding comparison, and returns a three-way decision.

                    Read `decision`, not `similarity`. A positive identification (MATCH) \
                    requires a decisive score AND two usable images AND a passed liveness \
                    check AND one face in the live frame. Anything short of that is \
                    UNCERTAIN, with `blockers` saying why and `recaptureAdvice` saying what \
                    to do - a marginal comparison is never rounded up to a match.

                    For a full document screening with tampering detection and watchlist \
                    checks, use POST /api/screenings.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Comparison completed."),
            @ApiResponse(responseCode = "400", description = "Missing or unreadable image.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "413", description = "An upload exceeds the size limit.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))})
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public FaceAuthResult compare(
            @Parameter(description = "Document image containing the portrait photo.",
                    required = true)
            @RequestPart("document") MultipartFile document,

            @Parameter(description = "Live camera capture of the person.", required = true)
            @RequestPart("live") MultipartFile live) throws IOException {

        Uploads.requireImage(document, "document");
        Uploads.requireImage(live, "live capture");

        ModuleResult result = faceVerificationService.verify(
                document.getBytes(), live.getBytes());

        Map<String, Object> details = result.details();

        boolean documentFaceFound = result.flags().stream()
                .noneMatch(f -> "FACE_NOT_FOUND_ON_DOCUMENT".equals(f.code()));
        boolean liveFaceFound = result.flags().stream()
                .noneMatch(f -> "FACE_NOT_FOUND_IN_CAPTURE".equals(f.code()));

        // The module reports SKIPPED when no matcher is configured and FAILED when one
        // broke. Neither is a comparison, and neither may be rendered as one.
        if (result.status() != ModuleResult.Status.COMPLETED) {
            return new FaceAuthResult(
                    0.0, "UNCERTAIN", 0.0,
                    result.note() == null
                            ? "No face comparison was performed." : result.note(),
                    List.of(), List.of(result.note() == null ? "Module 4 did not run."
                            : result.note()),
                    "Configure a face matching service and try again.",
                    documentFaceFound, liveFaceFound,
                    String.valueOf(details.getOrDefault("engine", "none")),
                    0.0, false, details.get("quality"), thresholds(), details);
        }

        return new FaceAuthResult(
                asDouble(details.get("similarity")),
                String.valueOf(details.getOrDefault("decision", "UNCERTAIN")),
                asDouble(details.get("confidence")),
                summaryFor(details, result.flags()),
                asStrings(details.get("reasons")),
                blockersFor(details, result.flags()),
                details.get("recaptureAdvice") == null
                        ? null : String.valueOf(details.get("recaptureAdvice")),
                documentFaceFound,
                liveFaceFound,
                String.valueOf(details.getOrDefault("engine", "unknown")),
                asDouble(details.get("livenessScore")),
                Boolean.TRUE.equals(details.get("livenessLive")),
                details.get("quality"),
                thresholds(),
                details);
    }

    // ------------------------------------------------------------------
    // Enrolment
    // ------------------------------------------------------------------

    @Operation(summary = "Enrol a reference face for a known traveller",
            description = """
                    Stores an L2-normalised face embedding against a subject, so a repeat \
                    crossing can be matched without falling back on the printed portrait - \
                    the weakest image in the pipeline.

                    The image must clear the quality gate. A poor enrolment is not a one-off \
                    wrong answer: it becomes a permanent source of wrong answers for every \
                    future comparison against that person. Set `acceptPoorQuality=true` to \
                    override, which is recorded in the audit trail.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The stored enrolment."),
            @ApiResponse(responseCode = "400",
                    description = "Missing subject, unusable image, or more than one face.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))})
    @PostMapping(path = "/enrolments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FaceEnrolment enrol(
            @Parameter(description = "Reference image of the traveller.", required = true)
            @RequestPart("image") MultipartFile image,

            @Parameter(description = "Stable identifier for this person.", required = true)
            @RequestParam("subjectId") String subjectId,

            @Parameter(description = "Name shown in the console.")
            @RequestParam(value = "displayName", required = false) String displayName,

            @Parameter(description = "Document number the enrolment relates to.")
            @RequestParam(value = "documentNumber", required = false) String documentNumber,

            @Parameter(description = "Nationality, when known.")
            @RequestParam(value = "nationality", required = false) String nationality,

            @Parameter(description = "Whether the image is a document portrait or a live capture.",
                    schema = @Schema(allowableValues = {"live", "document"}))
            @RequestParam(value = "source", defaultValue = "live") String source,

            @Parameter(description = "Officer enrolling, recorded in the audit trail.")
            @RequestParam(value = "enrolledBy", required = false) String enrolledBy,

            @Parameter(description = "Free-text note.")
            @RequestParam(value = "notes", required = false) String notes,

            @Parameter(description = "Enrol even though the image fails the quality gate.")
            @RequestParam(value = "acceptPoorQuality", defaultValue = "false")
            boolean acceptPoorQuality) throws IOException {

        Uploads.requireImage(image, "reference");

        return enrolmentService.enrol(new FaceEnrolmentService.EnrolmentRequest(
                image.getBytes(), source, subjectId, displayName, documentNumber,
                nationality, enrolledBy, notes, acceptPoorQuality));
    }

    @Operation(summary = "List face enrolments, newest first",
            description = "Includes withdrawn enrolments; check the `active` field. "
                    + "`size` is capped at 200.")
    @GetMapping("/enrolments")
    public PagedModel<FaceEnrolment> listEnrolments(
            @Parameter(description = "Zero-based page index.")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Rows per page. Values above 200 are clamped.")
            @RequestParam(defaultValue = "50") int size) {
        return new PagedModel<>(enrolmentRepository.findAllByOrderByEnrolledAtDesc(
                PageRequest.of(Math.max(0, page), Math.clamp(size, 1, 200))));
    }

    @Operation(summary = "Withdraw a face enrolment",
            description = "Marks the enrolment inactive so it is no longer matched against. "
                    + "It is never deleted - the record that a face was once enrolled is "
                    + "itself part of the audit trail.")
    @DeleteMapping("/enrolments/{id}")
    public FaceEnrolment withdraw(
            @Parameter(description = "Internal id of the enrolment.")
            @PathVariable String id,
            @Parameter(description = "Who withdrew it, recorded in the audit trail.")
            @RequestParam(required = false) String actor) {
        return enrolmentService.deactivate(id, actor);
    }

    // ------------------------------------------------------------------
    // 1:N identification
    // ------------------------------------------------------------------

    @Operation(summary = "Identify a face against the enrolled gallery (1:N)",
            description = """
                    Answers "who is this?" rather than "is this who they claim to be?".

                    Held to a stricter bar than verification, and deliberately so. Every \
                    extra enrolled subject is another chance for a coincidental high score, \
                    so the top candidate must clear a higher threshold AND beat the highest- \
                    scoring *different* person by a clear margin. Two near-equal candidates \
                    are reported as AMBIGUOUS rather than resolved - that is a pair of \
                    lookalikes, not an identification.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Identification attempted."),
            @ApiResponse(responseCode = "400", description = "Missing or unreadable image.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))})
    @PostMapping(path = "/identify", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public FaceEnrolmentService.IdentificationResult identify(
            @Parameter(description = "Live capture of the person to identify.", required = true)
            @RequestPart("image") MultipartFile image,
            @Parameter(description = "How many candidates to return. Capped at 25.")
            @RequestParam(defaultValue = "5") int limit) throws IOException {

        Uploads.requireImage(image, "capture");
        return enrolmentService.identify(image.getBytes(), Math.clamp(limit, 1, 25));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Map<String, Object> thresholds() {
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("match", faceVerificationService.matchThreshold());
        thresholds.put("mismatch", faceVerificationService.mismatchThreshold());
        thresholds.put("scale", "raw-cosine");
        return thresholds;
    }

    /** Prefers the matcher's own sentence, falling back to the flag that was raised. */
    private static String summaryFor(Map<String, Object> details, List<RiskFlag> flags) {
        Object summary = details.get("summary");
        if (summary != null) {
            return String.valueOf(summary);
        }
        return flags.stream()
                .filter(f -> f.code().startsWith("FACE_"))
                .map(RiskFlag::message)
                .findFirst()
                .orElse("The traveller matches the portrait on the document.");
    }

    /** Blockers from the matcher, plus anything the screening flags added on top. */
    private static List<String> blockersFor(Map<String, Object> details, List<RiskFlag> flags) {
        List<String> blockers = new ArrayList<>(asStrings(details.get("blockers")));
        for (RiskFlag flag : flags) {
            if ("FACE_QUALITY_INSUFFICIENT".equals(flag.code())
                    || "FACE_LIVENESS_SUSPECT".equals(flag.code())) {
                blockers.add(flag.message());
            }
        }
        return blockers;
    }

    private static double asDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static List<String> asStrings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> strings = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null) {
                strings.add(String.valueOf(item));
            }
        }
        return strings;
    }
}
