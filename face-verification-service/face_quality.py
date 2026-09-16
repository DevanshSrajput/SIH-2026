"""Face image quality assessment.

A face embedding is only as trustworthy as the pixels it was computed from. A
blurred, under-exposed, tiny or sharply angled face produces an embedding that
drifts unpredictably - sometimes toward the wrong identity, which is exactly the
failure a border checkpoint cannot afford.

So quality is measured before any identity claim is made, and a pair that fails
the gate is never decided. It is returned as uncertain, with a reason the officer
can act on: move closer, turn on the light, hold still, face the camera.

Every check returns a score in [0, 1] where 1 is ideal. The aggregate is the
weighted mean, but a single hard failure (`blocking`) is enough to mark the face
unusable regardless of the aggregate - a razor-sharp, perfectly lit face that is
30 pixels across is still not identifiable.
"""

import logging
import math
from dataclasses import dataclass, field
from typing import Optional

import cv2
import numpy as np

from config import settings
from face_detector import DetectedFace

logger = logging.getLogger(__name__)


@dataclass
class QualityCheck:
    """One measured attribute of a face image."""

    name: str
    score: float
    passed: bool
    value: float
    reason: str
    blocking: bool = False

    def to_dict(self) -> dict:
        return {
            "name": self.name,
            "score": round(self.score, 4),
            "passed": self.passed,
            "value": round(self.value, 2),
            "reason": self.reason,
            "blocking": self.blocking,
        }


@dataclass
class QualityResult:
    """Aggregated quality verdict for one face."""

    score: float
    usable: bool
    checks: list[QualityCheck] = field(default_factory=list)
    issues: list[str] = field(default_factory=list)
    advice: str = ""

    def to_dict(self) -> dict:
        return {
            "score": round(self.score, 4),
            "usable": self.usable,
            "issues": self.issues,
            "advice": self.advice,
            "checks": [c.to_dict() for c in self.checks],
        }


class FaceQualityAssessor:
    """Measures whether a detected face is good enough to identify someone on."""

    WEIGHTS = {
        "resolution": 0.28,
        "sharpness": 0.26,
        "exposure": 0.18,
        "contrast": 0.12,
        "pose": 0.16,
    }

    # Advice is ordered by what an officer should try first.
    ADVICE = {
        "resolution": "Move the traveller closer to the camera, or scan the document at a higher resolution.",
        "sharpness": "Hold still and re-capture; the face is blurred.",
        "exposure": "Adjust the lighting; the face is too dark or too bright.",
        "contrast": "Improve the lighting; the face is washed out.",
        "pose": "Ask the traveller to look straight at the camera and keep their head level.",
        "detection": "Re-capture; the face was only weakly detected.",
    }

    def assess(
        self,
        image: np.ndarray,
        face: DetectedFace,
        *,
        source: str = "live",
    ) -> QualityResult:
        """
        Assess one detected face.

        Args:
            image: full BGR frame the face was detected in.
            face: the detection to assess.
            source: ``"live"`` or ``"document"``. Document portraits are printed
                small and scanned, so they are held to a lower resolution bar.
        """
        x1, y1, x2, y2 = face.expanded_roi(image.shape, padding=0.1)
        roi = image[y1:y2, x1:x2]

        if roi.size == 0:
            return QualityResult(
                score=0.0,
                usable=False,
                checks=[],
                issues=["Face region is empty"],
                advice=self.ADVICE["detection"],
            )

        gray = cv2.cvtColor(roi, cv2.COLOR_BGR2GRAY)

        checks = [
            self._check_resolution(face, source),
            self._check_sharpness(gray),
            self._check_exposure(gray),
            self._check_contrast(gray),
            self._check_pose(face),
            self._check_detection_confidence(face, source),
        ]

        weighted = 0.0
        total_weight = 0.0
        for check in checks:
            weight = self.WEIGHTS.get(check.name)
            if weight is None:
                continue
            weighted += check.score * weight
            total_weight += weight

        score = weighted / total_weight if total_weight > 0 else 0.0
        score = max(0.0, min(1.0, score))

        blocked = [c for c in checks if c.blocking and not c.passed]
        failed = [c for c in checks if not c.passed]

        # Every individual check has to pass, not just the weighted average.
        #
        # The average alone is not a gate: a face at mean luminance 32 - far too
        # dark to identify anyone on - still averages well above the bar once a
        # generous resolution and sharpness score are folded in, and the image
        # sails through with its failed exposure check recorded and ignored.
        # An attribute measured out of range is a reason not to decide, whatever
        # the other attributes say.
        usable = score >= settings.QUALITY_MIN_SCORE and not blocked and not failed

        issues = [c.reason for c in failed]
        advice = ""
        if failed:
            # Lead with whatever is blocking, else with the weakest check.
            lead = blocked[0] if blocked else min(failed, key=lambda c: c.score)
            advice = self.ADVICE.get(lead.name, "Re-capture the image.")
        elif not usable:
            advice = "Overall image quality is marginal; re-capture for a reliable comparison."

        return QualityResult(
            score=score,
            usable=usable,
            checks=checks,
            issues=issues,
            advice=advice,
        )

    # -- individual checks ------------------------------------------------

    def _check_resolution(self, face: DetectedFace, source: str) -> QualityCheck:
        """Face size in pixels. Below roughly 60px across, SFace has nothing to work with."""
        minimum = (
            settings.QUALITY_MIN_FACE_PX_DOCUMENT
            if source == "document"
            else settings.QUALITY_MIN_FACE_PX_LIVE
        )
        shortest = float(min(face.width, face.height))
        passed = shortest >= minimum
        # Full marks at twice the minimum; nothing below half of it.
        score = max(0.0, min(1.0, (shortest - minimum * 0.5) / (minimum * 1.5)))

        return QualityCheck(
            name="resolution",
            score=score,
            passed=passed,
            value=shortest,
            reason=(
                f"Face is {shortest:.0f}px across, below the {minimum}px minimum"
                if not passed
                else f"Face is {shortest:.0f}px across"
            ),
            # A face this small cannot be identified no matter how good the rest is.
            blocking=shortest < minimum * 0.75,
        )

    def _check_sharpness(self, gray: np.ndarray) -> QualityCheck:
        """Variance of the Laplacian - the standard no-reference blur measure.

        Normalised against a fixed 128px reference width so that a large, sharp
        face and a small, sharp face score alike; the raw variance rises with
        resolution and would otherwise reward nothing but a big crop.
        """
        reference = 128
        h, w = gray.shape[:2]
        if w != reference:
            scale = reference / float(max(w, 1))
            resized = cv2.resize(
                gray,
                (reference, max(1, int(round(h * scale)))),
                interpolation=cv2.INTER_AREA,
            )
        else:
            resized = gray

        variance = float(cv2.Laplacian(resized, cv2.CV_64F).var())
        passed = variance >= settings.QUALITY_MIN_SHARPNESS
        score = max(0.0, min(1.0, variance / (settings.QUALITY_MIN_SHARPNESS * 3.0)))

        return QualityCheck(
            name="sharpness",
            score=score,
            passed=passed,
            value=variance,
            reason=(
                f"Face is blurred (sharpness {variance:.0f}, minimum {settings.QUALITY_MIN_SHARPNESS:.0f})"
                if not passed
                else f"Sharpness {variance:.0f}"
            ),
            # Severe blur destroys the embedding entirely.
            blocking=variance < settings.QUALITY_MIN_SHARPNESS * 0.45,
        )

    def _check_exposure(self, gray: np.ndarray) -> QualityCheck:
        """Mean luminance, plus the share of pixels clipped to pure black or white."""
        mean = float(np.mean(gray))
        clipped_low = float(np.mean(gray < 8))
        clipped_high = float(np.mean(gray > 247))
        clipped = clipped_low + clipped_high

        too_dark = mean < settings.QUALITY_MIN_BRIGHTNESS
        too_bright = mean > settings.QUALITY_MAX_BRIGHTNESS
        heavily_clipped = clipped > 0.25
        passed = not (too_dark or too_bright or heavily_clipped)

        # Ideal sits mid-range; score falls off toward either end.
        midpoint = (settings.QUALITY_MIN_BRIGHTNESS + settings.QUALITY_MAX_BRIGHTNESS) / 2.0
        half_range = (settings.QUALITY_MAX_BRIGHTNESS - settings.QUALITY_MIN_BRIGHTNESS) / 2.0
        score = 1.0 - min(1.0, abs(mean - midpoint) / max(half_range, 1.0))
        score = max(0.0, score - clipped)

        if too_dark:
            reason = f"Face is under-exposed (mean luminance {mean:.0f})"
        elif too_bright:
            reason = f"Face is over-exposed (mean luminance {mean:.0f})"
        elif heavily_clipped:
            reason = f"{clipped * 100:.0f}% of the face is clipped to pure black or white"
        else:
            reason = f"Mean luminance {mean:.0f}"

        return QualityCheck(
            name="exposure",
            score=score,
            passed=passed,
            value=mean,
            reason=reason,
            blocking=mean < 25 or mean > 235 or clipped > 0.45,
        )

    def _check_contrast(self, gray: np.ndarray) -> QualityCheck:
        """Standard deviation of luminance. A flat face carries no usable detail."""
        std = float(np.std(gray))
        passed = std >= settings.QUALITY_MIN_CONTRAST
        score = max(0.0, min(1.0, std / (settings.QUALITY_MIN_CONTRAST * 2.5)))

        return QualityCheck(
            name="contrast",
            score=score,
            passed=passed,
            value=std,
            reason=(
                f"Face is low-contrast (std {std:.0f}, minimum {settings.QUALITY_MIN_CONTRAST:.0f})"
                if not passed
                else f"Contrast {std:.0f}"
            ),
            blocking=std < settings.QUALITY_MIN_CONTRAST * 0.4,
        )

    def _check_pose(self, face: DetectedFace) -> QualityCheck:
        """Head rotation, estimated from YuNet's five landmarks.

        Roll comes straight from the angle of the eye line. Yaw is estimated from
        how far the nose sits from the midpoint between the eyes, as a fraction of
        the inter-ocular distance - a head turned away pushes the nose toward one
        eye. Neither is a calibrated 3D pose, but both are enough to catch the
        profile shots that wreck a comparison.
        """
        landmarks = face.landmarks
        if landmarks is None or len(landmarks) < 5:
            # Cannot measure it; do not punish the face for the detector's silence.
            return QualityCheck(
                name="pose",
                score=0.75,
                passed=True,
                value=0.0,
                reason="Pose not measurable (no landmarks)",
            )

        right_eye, left_eye, nose = landmarks[0], landmarks[1], landmarks[2]

        dx = float(left_eye[0] - right_eye[0])
        dy = float(left_eye[1] - right_eye[1])
        roll = abs(math.degrees(math.atan2(dy, dx)))
        if roll > 90.0:
            roll = 180.0 - roll

        interocular = math.hypot(dx, dy)
        if interocular < 1e-3:
            yaw_ratio = 0.0
        else:
            eye_mid_x = (float(left_eye[0]) + float(right_eye[0])) / 2.0
            yaw_ratio = abs(float(nose[0]) - eye_mid_x) / interocular

        roll_ok = roll <= settings.QUALITY_MAX_ROLL_DEGREES
        yaw_ok = yaw_ratio <= settings.QUALITY_MAX_YAW_RATIO
        passed = roll_ok and yaw_ok

        roll_score = 1.0 - min(1.0, roll / max(settings.QUALITY_MAX_ROLL_DEGREES * 2.0, 1.0))
        yaw_score = 1.0 - min(1.0, yaw_ratio / max(settings.QUALITY_MAX_YAW_RATIO * 2.0, 1e-3))
        score = min(roll_score, yaw_score)

        if not yaw_ok:
            reason = f"Head is turned away from the camera (yaw ratio {yaw_ratio:.2f})"
        elif not roll_ok:
            reason = f"Head is tilted {roll:.0f} degrees"
        else:
            reason = f"Roll {roll:.0f} degrees, yaw ratio {yaw_ratio:.2f}"

        return QualityCheck(
            name="pose",
            score=max(0.0, min(1.0, score)),
            passed=passed,
            value=max(roll, yaw_ratio * 100.0),
            reason=reason,
            blocking=yaw_ratio > settings.QUALITY_MAX_YAW_RATIO * 1.8
            or roll > settings.QUALITY_MAX_ROLL_DEGREES * 2.0,
        )

    def _check_detection_confidence(self, face: DetectedFace, source: str) -> QualityCheck:
        """How sure the detector was that this is a face at all.

        Document portraits clear a lower bar. They are printed at low resolution,
        overprinted with security patterns and re-scanned, all of which cost the
        detector confidence without saying anything about whether the face is
        usable - the same allowance the resolution check makes.
        """
        confidence = float(face.confidence)
        minimum = (
            settings.MIN_DECISION_CONFIDENCE * 0.85
            if source == "document"
            else settings.MIN_DECISION_CONFIDENCE
        )
        passed = confidence >= minimum

        return QualityCheck(
            name="detection",
            score=max(0.0, min(1.0, confidence)),
            passed=passed,
            value=confidence,
            reason=(
                f"Face detected with low confidence ({confidence:.2f}, minimum {minimum:.2f})"
                if not passed
                else f"Detection confidence {confidence:.2f}"
            ),
            blocking=confidence < minimum * 0.75,
        )


face_quality_assessor = FaceQualityAssessor()


def assess_quality(
    image: np.ndarray, face: Optional[DetectedFace], *, source: str = "live"
) -> QualityResult:
    """Convenience wrapper that tolerates a missing detection."""
    if face is None:
        return QualityResult(
            score=0.0,
            usable=False,
            issues=["No face was detected"],
            advice=FaceQualityAssessor.ADVICE["detection"],
        )
    return face_quality_assessor.assess(image, face, source=source)
