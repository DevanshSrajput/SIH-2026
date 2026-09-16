package com.govid.screening.face;

/**
 * The three answers a face comparison is allowed to give.
 *
 * <p>There is no fourth value meaning "probably". A biometric comparison either
 * supports an identification, contradicts it, or does not settle it - and the last
 * of those is a real answer that an officer can act on, not a failure to produce one.
 * Collapsing it into either of the other two is what turns a marginal score into a
 * waved-through impostor or a detained traveller who has done nothing wrong.
 */
public enum FaceDecision {

    /** The traveller is the person the document was issued to. */
    MATCH,

    /** The traveller is not that person. */
    NO_MATCH,

    /**
     * The comparison does not settle it, and an officer must look.
     *
     * <p>Reached either because the score itself is inconclusive, or because
     * something about the capture - blur, lighting, pose, a failed liveness check,
     * two faces in frame - means the score cannot be trusted even though it looks
     * decisive.
     */
    UNCERTAIN
}
