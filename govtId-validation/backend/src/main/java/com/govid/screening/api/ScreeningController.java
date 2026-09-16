package com.govid.screening.api;

import com.govid.screening.api.dto.ApiError;
import com.govid.screening.api.dto.CaseSummary;
import com.govid.screening.api.dto.DecisionRequest;
import com.govid.screening.domain.AuditEvent;
import com.govid.screening.domain.DocumentType;
import com.govid.screening.domain.ScreeningCase;
import com.govid.screening.pipeline.ImageStore;
import com.govid.screening.pipeline.ScreeningService;
import com.govid.screening.repository.AuditEventRepository;
import com.govid.screening.repository.ScreeningCaseRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/** Officer-facing screening API. */
@RestController
@RequestMapping("/api/screenings")
@Tag(name = "Screenings",
        description = "Submit documents, retrieve cases, evidence images and the audit trail, "
                + "and record an officer decision.")
public class ScreeningController {

    private final ScreeningService screeningService;
    private final ScreeningCaseRepository caseRepository;
    private final AuditEventRepository auditRepository;
    private final ImageStore imageStore;

    public ScreeningController(ScreeningService screeningService,
                               ScreeningCaseRepository caseRepository,
                               AuditEventRepository auditRepository,
                               ImageStore imageStore) {
        this.screeningService = screeningService;
        this.caseRepository = caseRepository;
        this.auditRepository = auditRepository;
        this.imageStore = imageStore;
    }

    /**
     * Screens one presented document.
     *
     * @param document    the document image; required
     * @param live        live capture of the traveller, enabling Module 4; optional
     * @param text        text the caller already holds, such as an e-passport chip read.
     *                    When present it is trusted over pixel OCR.
     */
    @Operation(summary = "Screen a presented document",
            description = """
                    Runs the full pipeline over one document and returns the completed case, \
                    including the per-module results and the aggregated risk assessment.

                    Supplying `live` enables face verification (Module 4); without it that \
                    module reports that it did not run rather than guessing a similarity score. \
                    Supplying `text` - a chip read, for instance - is trusted over pixel OCR.

                    The verdict is a recommendation. Record what the officer actually decided \
                    with `POST /api/screenings/{reference}/decision`.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Screening completed."),
            @ApiResponse(responseCode = "400", description = "No document image was supplied.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class))),
            @ApiResponse(responseCode = "413", description = "The upload exceeds the size limit.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))})
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ScreeningCase screen(
            @Parameter(description = "The presented document image.", required = true)
            @RequestPart("document") MultipartFile document,

            @Parameter(description = "Live capture of the traveller. Enables Module 4.")
            @RequestPart(value = "live", required = false) MultipartFile live,

            @Parameter(description = "Document type, when the lane already knows it.")
            @RequestParam(value = "documentType", defaultValue = "UNKNOWN") DocumentType documentType,

            @Parameter(description = "Checkpoint the screening happened at.", example = "LHR-T5")
            @RequestParam(value = "checkpointId", required = false) String checkpointId,

            @Parameter(description = "Lane within the checkpoint.", example = "LANE-04")
            @RequestParam(value = "laneId", required = false) String laneId,

            @Parameter(description = "Officer operating the lane, recorded in the audit trail.")
            @RequestParam(value = "officerId", required = false) String officerId,

            @Parameter(description = "Text the caller already holds, such as an e-passport chip "
                    + "read. Trusted over pixel OCR when present.")
            @RequestParam(value = "text", required = false) String text) throws IOException {

        if (document == null || document.isEmpty()) {
            throw new IllegalArgumentException("A document image is required.");
        }

        return screeningService.screen(new ScreeningService.ScreeningRequest(
                document.getBytes(),
                document.getContentType(),
                live == null || live.isEmpty() ? null : live.getBytes(),
                live == null ? null : live.getContentType(),
                documentType,
                checkpointId,
                laneId,
                officerId,
                text));
    }

    @Operation(summary = "List cases, newest first",
            description = "Returns summary rows rather than whole cases so the list stays fast "
                    + "under load. `size` is capped at 100.")
    @GetMapping
    public Page<CaseSummary> list(
            @Parameter(description = "Zero-based page index.")
            @RequestParam(defaultValue = "0") int page,
            @Parameter(description = "Rows per page. Values above 100 are clamped.")
            @RequestParam(defaultValue = "25") int size) {
        return caseRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, Math.min(size, 100)))
                .map(CaseSummary::from);
    }

    @Operation(summary = "Fetch one case in full",
            description = "Accepts either the human-readable case reference or the internal id.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The case."),
            @ApiResponse(responseCode = "400", description = "No such case.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))})
    @GetMapping("/{reference}")
    public ScreeningCase get(
            @Parameter(description = "Case reference or internal id.", example = "BRD-K7M2QX")
            @PathVariable String reference) {
        return caseRepository.findByCaseReference(reference)
                .or(() -> caseRepository.findById(reference))
                .orElseThrow(() -> new IllegalArgumentException("Unknown case " + reference));
    }

    @Operation(summary = "Audit trail for a case",
            description = "Every recorded event for the case, oldest first. This is the record "
                    + "that makes a past decision re-checkable.")
    @GetMapping("/{reference}/audit")
    public List<AuditEvent> audit(
            @Parameter(description = "Case reference or internal id.", example = "BRD-K7M2QX")
            @PathVariable String reference) {
        ScreeningCase screeningCase = get(reference);
        return auditRepository.findByCaseIdOrderByOccurredAtAsc(screeningCase.getId());
    }

    /**
     * Serves a stored evidence image.
     *
     * @param kind {@code document} or {@code live}
     */
    @Operation(summary = "Fetch a stored evidence image",
            description = "Returns the bytes as stored, with their original content type.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The image.",
                    content = @Content(mediaType = "image/*",
                            schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "404",
                    description = "The case holds no image of that kind.", content = @Content),
            @ApiResponse(responseCode = "400",
                    description = "No such case, or an unknown image kind.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))})
    @GetMapping("/{reference}/images/{kind}")
    public ResponseEntity<byte[]> image(
            @Parameter(description = "Case reference or internal id.", example = "BRD-K7M2QX")
            @PathVariable String reference,
            @Parameter(description = "Which stored image to serve.",
                    schema = @Schema(allowableValues = {"document", "live"}))
            @PathVariable String kind) {
        ScreeningCase screeningCase = get(reference);
        String imageId = switch (kind) {
            case "document" -> screeningCase.getDocumentImageId();
            case "live" -> screeningCase.getLiveCaptureImageId();
            default -> throw new IllegalArgumentException("Unknown image kind " + kind);
        };
        if (imageId == null) {
            return ResponseEntity.notFound().build();
        }
        ImageStore.StoredImage image = imageStore.load(imageId);
        if (image == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        image.contentType() == null ? "application/octet-stream" : image.contentType()))
                .body(image.bytes());
    }

    @Operation(summary = "Record the officer's decision",
            description = "Stored alongside the system recommendation rather than replacing it, "
                    + "so a disagreement between the two remains visible afterwards.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The updated case."),
            @ApiResponse(responseCode = "400",
                    description = "No such case, or the decision is missing.",
                    content = @Content(mediaType = "application/json",
                            schema = @Schema(implementation = ApiError.class)))})
    @PostMapping("/{reference}/decision")
    public ScreeningCase decide(
            @Parameter(description = "Case reference or internal id.", example = "BRD-K7M2QX")
            @PathVariable String reference,
            @Valid @RequestBody DecisionRequest request) {
        ScreeningCase screeningCase = get(reference);
        return screeningService.recordDecision(
                screeningCase.getId(), request.decision(), request.officerId(), request.notes());
    }
}
