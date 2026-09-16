package com.govid.screening.pipeline;

import com.govid.screening.domain.AuditEvent;
import com.govid.screening.domain.DocumentType;
import com.govid.screening.domain.ModuleResult;
import com.govid.screening.domain.RiskAssessment;
import com.govid.screening.domain.ScreeningCase;
import com.govid.screening.domain.Verdict;
import com.govid.screening.face.FaceVerificationService;
import com.govid.screening.ocr.OcrEngine;
import com.govid.screening.ocr.OcrService;
import com.govid.screening.repository.AuditEventRepository;
import com.govid.screening.repository.ScreeningCaseRepository;
import com.govid.screening.risk.RiskEngine;
import com.govid.screening.support.IdentityKeys;
import com.govid.screening.tampering.TamperingService;
import com.govid.screening.validation.DocumentValidationService;
import com.govid.screening.watchlist.WatchlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the four screening modules and the watchlist stage for one presented
 * document.
 *
 * <p>Runs the modules in dependency order - later stages need Module 1's extracted fields -
 * and collects their results without letting any one of them abort the run. The case is
 * persisted whatever happens, because a screening that failed halfway is itself something
 * an investigator may need to see.
 */
@Service
public class ScreeningService {

    private static final Logger log = LoggerFactory.getLogger(ScreeningService.class);

    /** Excludes I, O, 0 and 1 so a reference read aloud over a radio is unambiguous. */
    private static final char[] REFERENCE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OcrService ocrService;
    private final DocumentValidationService validationService;
    private final TamperingService tamperingService;
    private final FaceVerificationService faceVerificationService;
    private final WatchlistService watchlistService;
    private final RiskEngine riskEngine;
    private final ScreeningCaseRepository caseRepository;
    private final AuditEventRepository auditRepository;
    private final ImageStore imageStore;
    private final Clock clock;

    public ScreeningService(OcrService ocrService,
                            DocumentValidationService validationService,
                            TamperingService tamperingService,
                            FaceVerificationService faceVerificationService,
                            WatchlistService watchlistService,
                            RiskEngine riskEngine,
                            ScreeningCaseRepository caseRepository,
                            AuditEventRepository auditRepository,
                            ImageStore imageStore,
                            Clock clock) {
        this.ocrService = ocrService;
        this.validationService = validationService;
        this.tamperingService = tamperingService;
        this.faceVerificationService = faceVerificationService;
        this.watchlistService = watchlistService;
        this.riskEngine = riskEngine;
        this.caseRepository = caseRepository;
        this.auditRepository = auditRepository;
        this.imageStore = imageStore;
        this.clock = clock;
    }

    /**
     * One document presented at a lane.
     *
     * @param suppliedText text the caller already holds, such as a chip read. Optional.
     */
    public record ScreeningRequest(
            byte[] documentImage,
            String documentContentType,
            byte[] liveCapture,
            String liveCaptureContentType,
            DocumentType documentType,
            String checkpointId,
            String laneId,
            String officerId,
            String suppliedText) {
    }

    public ScreeningCase screen(ScreeningRequest request) {
        long start = System.nanoTime();

        ScreeningCase screeningCase = new ScreeningCase();
        screeningCase.setCaseReference(newCaseReference());
        screeningCase.setCheckpointId(request.checkpointId());
        screeningCase.setLaneId(request.laneId());
        screeningCase.setOfficerId(request.officerId());
        screeningCase.setDocumentType(request.documentType() == null
                ? DocumentType.UNKNOWN : request.documentType());
        screeningCase.setStatus(ScreeningCase.Status.PROCESSING);
        screeningCase.setCreatedAt(Instant.now(clock));

        // Persist the evidence before analysing it, so a crash mid-pipeline still leaves
        // the images recoverable against the case reference.
        screeningCase.setDocumentImageId(imageStore.store(
                request.documentImage(), "document", request.documentContentType()));
        screeningCase.setLiveCaptureImageId(imageStore.store(
                request.liveCapture(), "live-capture", request.liveCaptureContentType()));
        screeningCase = caseRepository.save(screeningCase);

        List<ModuleResult> results = new ArrayList<>();

        try {
            // Module 1 - OCR extraction.
            OcrService.OcrOutcome ocr = ocrService.extract(new OcrEngine.OcrRequest(
                    request.documentImage(),
                    request.documentContentType(),
                    screeningCase.getDocumentType(),
                    request.suppliedText()));
            results.add(ocr.result());
            screeningCase.setExtracted(ocr.fields());

            // Module 2 - standards and internal consistency.
            results.add(validationService.validate(screeningCase.getDocumentType(), ocr.fields()));

            // Module 3 - image forensics.
            results.add(tamperingService.analyse(
                    request.documentImage(), request.documentContentType()));

            // Module 4 - is the bearer the owner.
            results.add(faceVerificationService.verify(
                    request.documentImage(), request.liveCapture()));

            // Cross-case and watchlist screening.
            screeningCase.setDocumentNumberKey(IdentityKeys.documentNumberKey(ocr.fields()));
            screeningCase.setIdentityKey(IdentityKeys.identityKey(ocr.fields()));
            results.add(watchlistService.screen(ocr.fields(), screeningCase.getId()));

            RiskAssessment assessment = riskEngine.assess(results);
            screeningCase.setModuleResults(results);
            screeningCase.setRisk(assessment);
            screeningCase.setStatus(ScreeningCase.Status.COMPLETED);

        } catch (RuntimeException e) {
            log.error("Screening pipeline failed for case {}", screeningCase.getCaseReference(), e);
            screeningCase.setModuleResults(results);
            screeningCase.setStatus(ScreeningCase.Status.FAILED);
        }

        screeningCase.setCompletedAt(Instant.now(clock));
        screeningCase.setProcessingMillis((System.nanoTime() - start) / 1_000_000L);
        screeningCase = caseRepository.save(screeningCase);

        audit(screeningCase, request.officerId(), "SCREENED",
                "Document screened through the automated pipeline");

        return screeningCase;
    }

    /**
     * Records the officer's own determination.
     *
     * <p>The recommendation is never overwritten. Both the machine's view and the human's
     * are kept, because a divergence between them is exactly what a later review needs
     * to see.
     */
    public ScreeningCase recordDecision(String caseId, Verdict decision, String officerId,
                                        String notes) {
        ScreeningCase screeningCase = caseRepository.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown case " + caseId));

        screeningCase.setOfficerDecision(decision);
        screeningCase.setOfficerNotes(notes);
        screeningCase.setDecidedAt(Instant.now(clock));
        screeningCase.setOfficerId(officerId);
        screeningCase = caseRepository.save(screeningCase);

        Map<String, Object> data = new HashMap<>();
        data.put("officerDecision", decision.name());
        data.put("systemRecommendation", screeningCase.getRisk() == null
                ? null : screeningCase.getRisk().verdict().name());
        data.put("riskScore", screeningCase.getRisk() == null
                ? null : screeningCase.getRisk().score());
        data.put("notes", notes);

        auditRepository.save(new AuditEvent(screeningCase.getId(), officerId, "DECISION_RECORDED",
                "Officer recorded a decision of " + decision, data));

        return screeningCase;
    }

    private void audit(ScreeningCase screeningCase, String officerId, String action, String detail) {
        Map<String, Object> data = new HashMap<>();
        data.put("caseReference", screeningCase.getCaseReference());
        data.put("documentType", String.valueOf(screeningCase.getDocumentType()));
        data.put("status", String.valueOf(screeningCase.getStatus()));
        data.put("processingMillis", screeningCase.getProcessingMillis());
        if (screeningCase.getRisk() != null) {
            data.put("riskScore", screeningCase.getRisk().score());
            data.put("verdict", screeningCase.getRisk().verdict().name());
            data.put("flagCount", screeningCase.getRisk().flags().size());
        }
        auditRepository.save(new AuditEvent(
                screeningCase.getId(), officerId, action, detail, data));
    }

    private static String newCaseReference() {
        StringBuilder reference = new StringBuilder("BRD-");
        for (int i = 0; i < 6; i++) {
            reference.append(REFERENCE_ALPHABET[RANDOM.nextInt(REFERENCE_ALPHABET.length)]);
        }
        return reference.toString();
    }
}
