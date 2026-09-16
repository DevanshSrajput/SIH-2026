package com.govid.screening.ocr;

import com.govid.screening.domain.DocumentType;
import com.govid.screening.domain.ExtractedFields;
import com.govid.screening.domain.ModuleResult;
import com.govid.screening.domain.RiskFlag;
import com.govid.screening.domain.ScreeningModule;
import com.govid.screening.domain.Severity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Module 1 - OCR Extraction.
 *
 * <p>Picks the highest-priority available {@link OcrEngine}, reads the document, then
 * turns the text into structured fields. The MRZ is parsed first and its values win;
 * printed labels fill only what the MRZ did not supply.
 *
 * <p>This module reports on the <em>quality</em> of the read. Whether the values are
 * internally consistent or standards-compliant is Module 2's job.
 */
@Service
public class OcrService {

    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    /** Below this, the read is too poor to base a decision on without a human look. */
    private static final double LOW_CONFIDENCE_THRESHOLD = 0.60;

    /** Document types that are required by ICAO to carry a machine readable zone. */
    private static final List<DocumentType> MRZ_MANDATORY =
            List.of(DocumentType.PASSPORT, DocumentType.VISA, DocumentType.NATIONAL_ID);

    private final List<OcrEngine> engines;

    public OcrService(List<OcrEngine> engines) {
        this.engines = engines.stream()
                .sorted(Comparator.comparingInt(OcrEngine::priority))
                .toList();
    }

    /** Module 1 output: the findings and the fields the rest of the pipeline works from. */
    public record OcrOutcome(ModuleResult result, ExtractedFields fields) {
    }

    public OcrOutcome extract(OcrEngine.OcrRequest request) {
        long start = System.nanoTime();

        // Turn the photograph the way its EXIF says it should be viewed, before any engine
        // sees it. A sideways page does not read badly, it reads as noise, and the module
        // would then report every field missing on a document that was never in question.
        // Only the copy passed to the engines is turned; the stored evidence is untouched.
        ExifOrientation.Result oriented = ExifOrientation.upright(request.image());
        if (oriented.applied()) {
            request = new OcrEngine.OcrRequest(oriented.image(), request.contentType(),
                    request.declaredType(), request.suppliedText());
        }
        final OcrEngine.OcrRequest engineRequest = request;

        List<OcrEngine> candidates = engines.stream()
                .filter(engine -> engine.isAvailable(engineRequest))
                .toList();

        if (candidates.isEmpty()) {
            return new OcrOutcome(
                    ModuleResult.failed(ScreeningModule.OCR_EXTRACTION, elapsed(start),
                            "No OCR engine available. Install Tesseract, configure an Anthropic "
                                    + "credential, or supply the document text with the request."),
                    new ExtractedFields());
        }

        // Try each available engine in priority order rather than betting the module on the
        // first one. An engine can be available and still fail - a revoked credential, an
        // unreachable service, a binary that dies on one image - and when a working engine
        // sits behind it, failing the whole module would discard a document the platform
        // could actually have read.
        OcrEngine engine = null;
        OcrEngine.OcrOutput output = null;
        List<String> attempts = new ArrayList<>();

        for (OcrEngine candidate : candidates) {
            try {
                output = candidate.read(engineRequest);
                engine = candidate;
                break;
            } catch (Exception e) {
                log.warn("OCR engine {} failed; trying the next available engine",
                        candidate.name(), e);
                attempts.add(candidate.name() + ": " + rootMessage(e));
            }
        }

        if (output == null) {
            return new OcrOutcome(
                    ModuleResult.failed(ScreeningModule.OCR_EXTRACTION, elapsed(start),
                            "Every available OCR engine failed - " + String.join("; ", attempts)),
                    new ExtractedFields());
        }

        ExtractedFields fields = new ExtractedFields();
        fields.setEngine(engine.name());
        fields.setRawText(output.rawText());
        fields.setOcrConfidence(output.confidence());

        List<RiskFlag> flags = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>(output.details());
        details.put("engine", engine.name());
        details.put("confidence", output.confidence());
        details.put("exifOrientation", oriented.orientation());
        details.put("exifOrientationApplied", oriented.applied());
        if (!attempts.isEmpty()) {
            // Recorded as a diagnostic, never as a risk flag. A failing engine is our
            // infrastructure problem; it must not raise the score against the traveller.
            details.put("failedEngines", List.copyOf(attempts));
        }

        // Read the printed page on its own first. Captured before the MRZ is applied so
        // Module 2 can compare the two independent readings of the same document.
        ExtractedFields printedOnly = new ExtractedFields();
        LabelledFieldExtractor.enrich(printedOnly, output.rawText());
        fields.setPrintedSurname(printedOnly.getSurname());
        fields.setPrintedGivenNames(printedOnly.getGivenNames());
        fields.setPrintedDocumentNumber(printedOnly.getDocumentNumber());
        fields.setPrintedDateOfBirth(printedOnly.getDateOfBirth());
        fields.setPrintedDateOfExpiry(printedOnly.getDateOfExpiry());

        Optional<MrzParser.MrzParseResult> mrz = MrzParser.parseFromText(output.rawText());
        if (mrz.isPresent()) {
            applyMrz(fields, mrz.get());
            details.put("mrzFormat", mrz.get().mrz().format());
        } else {
            details.put("mrzFormat", null);
            Severity severity = MRZ_MANDATORY.contains(engineRequest.declaredType())
                    ? Severity.HIGH
                    : Severity.LOW;
            flags.add(RiskFlag.of("MRZ_NOT_FOUND", ScreeningModule.OCR_EXTRACTION, severity,
                    "No machine readable zone could be read from the document."
                            + (severity == Severity.HIGH
                            ? " A document of this type is required to carry one."
                            : ""),
                    Map.of("declaredType", String.valueOf(engineRequest.declaredType()))));
        }

        LabelledFieldExtractor.enrich(fields, output.rawText());

        if (output.confidence() < LOW_CONFIDENCE_THRESHOLD) {
            flags.add(RiskFlag.of("OCR_LOW_CONFIDENCE", ScreeningModule.OCR_EXTRACTION, Severity.MEDIUM,
                    "Text recovery confidence was %.0f%%; downstream checks may be unreliable."
                            .formatted(output.confidence() * 100),
                    Map.of("confidence", output.confidence(), "engine", engine.name())));
        }

        details.put("fieldsRecovered", countPopulated(fields));

        return new OcrOutcome(
                new ModuleResult(ScreeningModule.OCR_EXTRACTION, ModuleResult.Status.COMPLETED,
                        elapsed(start), flags, details,
                        attempts.isEmpty()
                                ? "Read by " + engine.name()
                                : "Read by " + engine.name() + " after "
                                        + attempts.size() + " engine(s) failed"),
                fields);
    }

    private static void applyMrz(ExtractedFields fields, MrzParser.MrzParseResult mrz) {
        fields.setMrz(mrz.mrz());
        fields.setSurname(mrz.surname());
        fields.setGivenNames(mrz.givenNames());
        fields.setDocumentNumber(mrz.documentNumber());
        fields.setIssuingState(mrz.issuingState());
        fields.setNationality(mrz.nationality());
        fields.setDateOfBirth(mrz.dateOfBirth());
        fields.setDateOfExpiry(mrz.dateOfExpiry());
        fields.setSex(mrz.sex());
        fields.setPersonalNumber(mrz.optionalData());

        // A visa MRZ carries the visa number in the document-number position.
        if (mrz.documentCode() != null && mrz.documentCode().startsWith("V")) {
            fields.setVisaNumber(mrz.documentNumber());
        }
    }

    /** Count of populated identity fields, used as a coverage signal on the console. */
    private static int countPopulated(ExtractedFields fields) {
        int count = 0;
        if (fields.getSurname() != null) count++;
        if (fields.getGivenNames() != null) count++;
        if (fields.getDocumentNumber() != null) count++;
        if (fields.getNationality() != null) count++;
        if (fields.getIssuingState() != null) count++;
        if (fields.getDateOfBirth() != null) count++;
        if (fields.getDateOfExpiry() != null) count++;
        if (fields.getSex() != null) count++;
        return count;
    }

    /** Innermost message of a failure chain, which is where the real cause usually sits. */
    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
