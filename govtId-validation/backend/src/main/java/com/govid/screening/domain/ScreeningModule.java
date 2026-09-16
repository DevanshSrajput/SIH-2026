package com.govid.screening.domain;

/** The four analysis modules that make up the screening pipeline. */
public enum ScreeningModule {
    OCR_EXTRACTION("Module 1 - OCR Extraction"),
    DOCUMENT_VALIDATION("Module 2 - Document Validation"),
    TAMPERING_DETECTION("Module 3 - Tampering Detection"),
    FACE_VERIFICATION("Module 4 - Face Verification"),
    WATCHLIST("Watchlist & Identity Screening");

    private final String label;

    ScreeningModule(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
