package com.govid.screening.face;

import java.util.List;
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
     * The outcome of one comparison.
     *
     * <p>A matcher that has assessed image quality and liveness for itself returns its own
     * {@code decision}, and the screening service takes it as given - the matcher saw the
     * pixels and the service did not. A matcher that only produces a raw score leaves
     * {@code decision} null, and the service applies its own thresholds instead.
     *
     * @param similarity        raw cosine similarity in [0, 1], where 1.0 is the same face.
     *                          Not a rescaled percentage: the thresholds are calibrated
     *                          against this scale directly.
     * @param documentFaceFound whether a face was located on the document image
     * @param liveFaceFound     whether a face was located in the live capture
     * @param engine            which matcher produced this
     * @param details           engine diagnostics kept for the audit trail
     * @param decision          the matcher's own three-way verdict, or null to let the
     *                          screening service decide from the score alone
     * @param confidence        how much weight the decision deserves, in [0, 1];
     *                          {@link Double#NaN} when the matcher does not report one
     * @param reasons           what drove the decision, in officer-readable terms
     * @param blockers          why a positive identification could not be issued, if it could not
     * @param summary           one sentence for the console
     * @param recaptureAdvice   what to do about an uncertain result, if anything
     */
    record FaceMatchResult(
            double similarity,
            boolean documentFaceFound,
            boolean liveFaceFound,
            String engine,
            Map<String, Object> details,
            FaceDecision decision,
            double confidence,
            List<String> reasons,
            List<String> blockers,
            String summary,
            String recaptureAdvice) {

        public FaceMatchResult {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            blockers = blockers == null ? List.of() : List.copyOf(blockers);
            details = details == null ? Map.of() : details;
        }

        /**
         * For a matcher that produces a score and nothing else. The screening service
         * applies its own thresholds to it.
         */
        public FaceMatchResult(double similarity, boolean documentFaceFound,
                               boolean liveFaceFound, String engine,
                               Map<String, Object> details) {
            this(similarity, documentFaceFound, liveFaceFound, engine, details,
                    null, Double.NaN, List.of(), List.of(), null, null);
        }

        /** Whether the matcher reported a confidence of its own. */
        public boolean hasConfidence() {
            return !Double.isNaN(confidence);
        }
    }
}
