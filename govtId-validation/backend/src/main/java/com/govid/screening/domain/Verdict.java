package com.govid.screening.domain;

/** Recommended action presented to the border officer. */
public enum Verdict {
    /** No material findings; passenger may proceed. */
    CLEAR,
    /** Findings require a human officer to inspect before deciding. */
    REVIEW,
    /** Strong evidence of forgery, tampering or a watchlist hit. */
    REJECT
}
