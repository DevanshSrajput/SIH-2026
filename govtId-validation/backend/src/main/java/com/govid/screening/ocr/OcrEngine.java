package com.govid.screening.ocr;

import com.govid.screening.domain.DocumentType;

import java.util.Map;

/**
 * Module 1 backend. Turns a document image into raw text.
 *
 * <p>Everything downstream of this interface (MRZ parsing, field extraction, validation)
 * is deterministic and engine-independent, so swapping Tesseract for a vision model or a
 * chip reader changes only which implementation wins {@link #priority()}.
 */
public interface OcrEngine {

    /** Stable identifier recorded on the case so a result can be reproduced later. */
    String name();

    /** Lower runs first. The first available engine handles the request. */
    int priority();

    /**
     * Whether this engine can run right now: binary installed, credential present,
     * or the caller supplied the text directly.
     */
    boolean isAvailable(OcrRequest request);

    OcrOutput read(OcrRequest request) throws Exception;

    /**
     * @param image        raw bytes of the uploaded document image
     * @param contentType  MIME type as received
     * @param declaredType document type the officer selected, used only as a hint
     * @param suppliedText text the caller already has (chip read, manual key-in, or a
     *                     test fixture); when present it is trusted over pixel OCR
     */
    record OcrRequest(
            byte[] image,
            String contentType,
            DocumentType declaredType,
            String suppliedText) {
    }

    /**
     * @param rawText    full text recovered from the document
     * @param confidence 0.0 - 1.0 self-reported confidence
     * @param details    engine-specific diagnostics kept for the audit trail
     */
    record OcrOutput(String rawText, double confidence, Map<String, Object> details) {
    }
}
