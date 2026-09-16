package com.govid.screening.ocr;

import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Uses text the caller already holds instead of reading pixels.
 *
 * <p>At a real checkpoint this is the e-passport chip read or the officer keying in the
 * MRZ from the printed page. It is deliberately the highest-priority engine: when the
 * text is known, pixel OCR can only introduce error.
 */
@Component
public class SuppliedTextOcrEngine implements OcrEngine {

    @Override
    public String name() {
        return "supplied-text";
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean isAvailable(OcrRequest request) {
        return request.suppliedText() != null && !request.suppliedText().isBlank();
    }

    @Override
    public OcrOutput read(OcrRequest request) {
        return new OcrOutput(
                request.suppliedText(),
                0.99,
                Map.of("source", "caller-supplied"));
    }
}
