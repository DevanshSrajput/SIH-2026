package com.govid.screening.face;

import java.util.Map;

/**
 * Module 4 backend: compares the portrait printed on the document with the live capture
 * of the person presenting it.
 *
 * <p>Face matching is a biometric problem that needs a trained model, and a wrong answer
 * here either waves through an impostor or detains a traveller who has done nothing. This
 * platform therefore does not approximate it. If no real matcher is configured, Module 4
 * reports that it did not run rather than inventing a similarity score - a screening
 * decision must never rest on a number that only looks like a measurement.
 */
public interface FaceVerifier {

    String name();

    /** Whether a real matcher is reachable and configured. */
    boolean isAvailable();

    FaceMatchResult compare(byte[] documentPortrait, byte[] liveCapture) throws Exception;

    /**
     * @param similarity          0.0 - 1.0, where 1.0 is the same face
     * @param documentFaceFound   whether a face was located on the document image
     * @param liveFaceFound       whether a face was located in the live capture
     * @param engine              which matcher produced this
     * @param details             engine diagnostics kept for the audit trail
     */
    record FaceMatchResult(
            double similarity,
            boolean documentFaceFound,
            boolean liveFaceFound,
            String engine,
            Map<String, Object> details) {
    }
}
