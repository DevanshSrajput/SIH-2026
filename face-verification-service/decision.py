"""The face comparison decision engine.

One job: turn a similarity score and a pile of evidence about image quality into
one of three answers - MATCH, NO_MATCH, or UNCERTAIN - and explain which evidence
drove it.

The design rule is that UNCERTAIN is the default, not the leftover. A positive
identification is only issued when the score is decisive *and* nothing about the
comparison undermines it. Anything else - a marginal score, a blurred capture, a
face turned away, a failed liveness check, two people in frame, a crop that could
not be landmark-aligned - is reported as uncertain with the reason attached, so an
officer knows to look rather than being handed a confident answer that happens to
be wrong.

The asymmetry is deliberate. At a checkpoint a false accept lets an impostor
through on someone else's document; a false uncertain costs a few seconds of an
officer's attention. Those are not the same mistake, and the thresholds are not
set as if they were.
"""

import logging
from dataclasses import dataclass, field
from enum import Enum
from typing import Optional

from config import settings
from face_quality import QualityResult

logger = logging.getLogger(__name__)


class Decision(str, Enum):
    """The three answers this service is allowed to give."""

    MATCH = "MATCH"
    NO_MATCH = "NO_MATCH"
    UNCERTAIN = "UNCERTAIN"


@dataclass
class DecisionResult:
    """A decision, its confidence, and the evidence behind it."""

    decision: Decision
    similarity: float
    confidence: float
    reasons: list[str] = field(default_factory=list)
    blockers: list[str] = field(default_factory=list)
    summary: str = ""
    recapture_advice: Optional[str] = None

    def to_dict(self) -> dict:
        return {
            "decision": self.decision.value,
            "similarity": round(self.similarity, 4),
            "confidence": round(self.confidence, 4),
            "reasons": self.reasons,
            "blockers": self.blockers,
            "summary": self.summary,
            "recaptureAdvice": self.recapture_advice,
        }


def decide(
    *,
    similarity: float,
    document_quality: QualityResult,
    live_quality: QualityResult,
    liveness_live: bool,
    liveness_score: float,
    liveness_reason: str = "",
    document_face_count: int = 1,
    live_face_count: int = 1,
    alignment_used: bool = True,
    liveness_enabled: bool = True,
) -> DecisionResult:
    """Decide whether the live capture is the person on the document.

    Args:
        similarity: raw cosine similarity in [-1, 1] between the two embeddings.
        document_quality: quality verdict for the document portrait.
        live_quality: quality verdict for the live capture.
        liveness_live: whether the live capture passed the anti-spoofing check.
        liveness_score: the liveness score, for reporting.
        liveness_reason: why liveness failed, if it did.
        document_face_count: how many faces were found on the document.
        live_face_count: how many faces were found in the live capture.
        alignment_used: whether both embeddings came from landmark-aligned crops.
        liveness_enabled: whether liveness gating is switched on for this deployment.
    """
    reasons: list[str] = []
    # Blockers are conditions that forbid a positive identification outright.
    blockers: list[str] = []
    recapture: Optional[str] = None

    match_at = settings.MATCH_THRESHOLD
    mismatch_at = settings.MISMATCH_THRESHOLD
    margin = settings.UNCERTAIN_MARGIN

    # ------------------------------------------------------------------
    # Evidence that forbids a positive identification.
    # ------------------------------------------------------------------
    if not document_quality.usable:
        blockers.append(
            "Document portrait quality is insufficient: "
            + (document_quality.issues[0] if document_quality.issues else "below the quality gate")
        )
        recapture = recapture or document_quality.advice

    if not live_quality.usable:
        blockers.append(
            "Live capture quality is insufficient: "
            + (live_quality.issues[0] if live_quality.issues else "below the quality gate")
        )
        recapture = recapture or live_quality.advice

    if liveness_enabled and not liveness_live:
        blockers.append(
            "Liveness check failed"
            + (f": {liveness_reason}" if liveness_reason else "")
            + ". The capture may be a printed photo or a screen replay."
        )
        recapture = recapture or "Re-capture the traveller directly from the camera."

    if live_face_count > 1:
        blockers.append(
            f"{live_face_count} faces are visible in the live capture, so it is not certain "
            "which person was compared."
        )
        recapture = recapture or "Re-capture with only the traveller in frame."

    if document_face_count > 1:
        # Not automatically disqualifying - some documents carry a ghost portrait -
        # but it is not something to decide an identity on silently.
        blockers.append(
            f"{document_face_count} faces were found on the document image; the highest-confidence "
            "one was used."
        )

    if not alignment_used:
        blockers.append(
            "A face could not be landmark-aligned, so the similarity score is less reliable "
            "than usual."
        )

    # ------------------------------------------------------------------
    # The score itself.
    # ------------------------------------------------------------------
    near_match_boundary = abs(similarity - match_at) < margin
    near_mismatch_boundary = abs(similarity - mismatch_at) < margin

    if similarity >= match_at:
        band = Decision.MATCH
        reasons.append(
            f"Similarity {similarity:.3f} is at or above the {match_at:.2f} match threshold."
        )
    elif similarity < mismatch_at:
        band = Decision.NO_MATCH
        reasons.append(
            f"Similarity {similarity:.3f} is below the {mismatch_at:.2f} mismatch threshold."
        )
    else:
        band = Decision.UNCERTAIN
        reasons.append(
            f"Similarity {similarity:.3f} falls between the {mismatch_at:.2f} and "
            f"{match_at:.2f} thresholds, which is neither a match nor a clear mismatch."
        )

    if band is Decision.MATCH and near_match_boundary:
        band = Decision.UNCERTAIN
        reasons.append(
            f"The score is within {margin:.2f} of the threshold, too close to call confidently."
        )
    elif band is Decision.NO_MATCH and near_mismatch_boundary:
        band = Decision.UNCERTAIN
        reasons.append(
            f"The score is within {margin:.2f} of the threshold, too close to call confidently."
        )

    # ------------------------------------------------------------------
    # Apply the blockers.
    #
    # A blocker can only ever move the answer toward UNCERTAIN. It never turns an
    # uncertain result into a match, and it never turns a poor-quality comparison
    # into an accusation: telling an officer the traveller is an impostor on the
    # strength of a dark, blurred photograph is its own kind of false positive.
    # ------------------------------------------------------------------
    decision = band
    if blockers:
        if band is Decision.MATCH:
            decision = Decision.UNCERTAIN
            reasons.append(
                "A positive identification was withheld because the comparison could not be "
                "fully trusted."
            )
        elif band is Decision.NO_MATCH:
            # A confident mismatch on good-enough evidence still stands; quality
            # problems only soften it when the evidence is genuinely unusable.
            unusable = not document_quality.usable or not live_quality.usable
            if unusable:
                decision = Decision.UNCERTAIN
                reasons.append(
                    "The faces look different, but the image quality is too poor to rule out "
                    "that the difference comes from the capture rather than the person."
                )

    confidence = _confidence(
        similarity=similarity,
        decision=decision,
        document_quality=document_quality,
        live_quality=live_quality,
        liveness_score=liveness_score,
        liveness_enabled=liveness_enabled,
        blocker_count=len(blockers),
    )

    summary = _summary(decision, similarity, blockers)

    if decision is Decision.UNCERTAIN and recapture is None:
        recapture = (
            "Compare the portrait and the traveller visually, or re-capture under better "
            "conditions and try again."
        )

    return DecisionResult(
        decision=decision,
        similarity=similarity,
        confidence=confidence,
        reasons=reasons,
        blockers=blockers,
        summary=summary,
        recapture_advice=recapture,
    )


def _confidence(
    *,
    similarity: float,
    decision: Decision,
    document_quality: QualityResult,
    live_quality: QualityResult,
    liveness_score: float,
    liveness_enabled: bool,
    blocker_count: int,
) -> float:
    """How much weight the decision deserves, in [0, 1].

    Distance from the threshold drives it, scaled down by the weaker of the two
    image qualities and by every unresolved blocker. This is reported separately
    from the similarity so the console can show a high similarity with a low
    confidence - which is exactly the situation an officer most needs to see.
    """
    match_at = settings.MATCH_THRESHOLD
    mismatch_at = settings.MISMATCH_THRESHOLD

    if decision is Decision.MATCH:
        # Saturates around 0.75 cosine, which is a strong same-person score.
        separation = (similarity - match_at) / max(0.75 - match_at, 1e-6)
    elif decision is Decision.NO_MATCH:
        # Saturates at 0.0 cosine, which is an unambiguously different person.
        separation = (mismatch_at - similarity) / max(mismatch_at, 1e-6)
    else:
        # In the uncertain band, confidence in the *uncertainty* is highest at the
        # midpoint and falls off toward either threshold.
        midpoint = (match_at + mismatch_at) / 2.0
        half_band = max((match_at - mismatch_at) / 2.0, 1e-6)
        separation = 1.0 - abs(similarity - midpoint) / half_band

    separation = max(0.0, min(1.0, separation))

    quality_factor = min(document_quality.score, live_quality.score)
    liveness_factor = max(0.0, min(1.0, liveness_score)) if liveness_enabled else 1.0

    confidence = separation * (0.55 + 0.45 * quality_factor)
    if liveness_enabled:
        confidence *= 0.7 + 0.3 * liveness_factor
    # Each unresolved caveat takes a further bite.
    confidence *= max(0.35, 1.0 - 0.15 * blocker_count)

    return max(0.0, min(1.0, confidence))


def _summary(decision: Decision, similarity: float, blockers: list[str]) -> str:
    """One sentence an officer can read off the screen."""
    if decision is Decision.MATCH:
        return (
            f"The traveller matches the portrait on the document (similarity {similarity:.2f})."
        )
    if decision is Decision.NO_MATCH:
        return (
            f"The traveller does not match the portrait on the document "
            f"(similarity {similarity:.2f}). Refer for manual inspection."
        )
    if blockers:
        return (
            "The system cannot say whether this is the same person - "
            + blockers[0].rstrip(".")
            + ". An officer must compare visually."
        )
    return (
        f"The system cannot say whether this is the same person (similarity {similarity:.2f} "
        "is inconclusive). An officer must compare visually."
    )
