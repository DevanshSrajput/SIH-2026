"""Tests for the face comparison decision engine and the quality gate.

The property under test throughout is one-sided: the service may be wrong by
being unsure, but it must not be wrong by being confident. Every path that could
produce a MATCH on evidence that does not support one is asserted here.
"""

import sys
import unittest
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).parent.parent))

from config import settings  # noqa: E402
from decision import Decision, decide  # noqa: E402
from face_detector import DetectedFace  # noqa: E402
from face_quality import QualityCheck, QualityResult, face_quality_assessor  # noqa: E402


def good_quality(score: float = 0.9) -> QualityResult:
    return QualityResult(score=score, usable=True, checks=[], issues=[], advice="")


def poor_quality(issue: str = "Face is blurred") -> QualityResult:
    return QualityResult(
        score=0.3,
        usable=False,
        checks=[],
        issues=[issue],
        advice="Hold still and re-capture.",
    )


def verdict(similarity: float, **overrides):
    """Run the decision engine with everything healthy unless overridden."""
    kwargs = dict(
        similarity=similarity,
        document_quality=good_quality(),
        live_quality=good_quality(),
        liveness_live=True,
        liveness_score=0.9,
        document_face_count=1,
        live_face_count=1,
        alignment_used=True,
        liveness_enabled=True,
    )
    kwargs.update(overrides)
    return decide(**kwargs)


class TestDecisionBands(unittest.TestCase):
    """The score alone, with everything else healthy."""

    def test_strong_score_matches(self):
        result = verdict(0.72)
        self.assertIs(result.decision, Decision.MATCH)
        self.assertGreater(result.confidence, 0.4)

    def test_low_score_is_no_match(self):
        result = verdict(0.05)
        self.assertIs(result.decision, Decision.NO_MATCH)

    def test_mid_band_is_uncertain(self):
        midpoint = (settings.MATCH_THRESHOLD + settings.MISMATCH_THRESHOLD) / 2.0
        result = verdict(midpoint)
        self.assertIs(result.decision, Decision.UNCERTAIN)
        self.assertTrue(result.summary)

    def test_score_just_over_threshold_is_uncertain_not_match(self):
        """A hair above the line is not a positive identification."""
        result = verdict(settings.MATCH_THRESHOLD + settings.UNCERTAIN_MARGIN / 2)
        self.assertIs(result.decision, Decision.UNCERTAIN)

    def test_score_just_under_mismatch_is_uncertain_not_rejection(self):
        result = verdict(settings.MISMATCH_THRESHOLD - settings.UNCERTAIN_MARGIN / 2)
        self.assertIs(result.decision, Decision.UNCERTAIN)

    def test_sface_break_even_alone_is_not_a_match(self):
        """SFace's published 0.363 break-even is inside our uncertain band.

        The published threshold balances false accepts against false rejects.
        A checkpoint does not want them balanced.
        """
        result = verdict(0.363)
        self.assertIsNot(result.decision, Decision.MATCH)


class TestNoFalsePositives(unittest.TestCase):
    """No combination of bad evidence may yield a MATCH."""

    def test_poor_document_quality_blocks_match(self):
        result = verdict(0.85, document_quality=poor_quality())
        self.assertIs(result.decision, Decision.UNCERTAIN)
        self.assertTrue(result.blockers)
        self.assertTrue(result.recapture_advice)

    def test_poor_live_quality_blocks_match(self):
        result = verdict(0.85, live_quality=poor_quality())
        self.assertIs(result.decision, Decision.UNCERTAIN)

    def test_failed_liveness_blocks_match(self):
        result = verdict(0.85, liveness_live=False, liveness_score=0.2,
                         liveness_reason="Low texture variance")
        self.assertIs(result.decision, Decision.UNCERTAIN)
        self.assertTrue(any("iveness" in b for b in result.blockers))

    def test_multiple_live_faces_block_match(self):
        result = verdict(0.85, live_face_count=3)
        self.assertIs(result.decision, Decision.UNCERTAIN)

    def test_unaligned_crop_blocks_match(self):
        result = verdict(0.85, alignment_used=False)
        self.assertIs(result.decision, Decision.UNCERTAIN)

    def test_every_blocker_at_once_still_uncertain_not_match(self):
        result = verdict(
            0.99,
            document_quality=poor_quality(),
            live_quality=poor_quality(),
            liveness_live=False,
            liveness_score=0.1,
            live_face_count=4,
            alignment_used=False,
        )
        self.assertIs(result.decision, Decision.UNCERTAIN)

    def test_a_blocker_never_creates_a_match(self):
        """Sweep the whole score range; a blocked comparison never matches."""
        for score in np.linspace(-1.0, 1.0, 81):
            result = verdict(float(score), liveness_live=False, liveness_score=0.1)
            self.assertIsNot(
                result.decision,
                Decision.MATCH,
                msg=f"similarity {score:.3f} produced a MATCH despite a failed liveness check",
            )


class TestNoFalseAccusations(unittest.TestCase):
    """A poor image must not be turned into an impostor accusation either."""

    def test_unusable_images_soften_a_mismatch_to_uncertain(self):
        result = verdict(0.02, live_quality=poor_quality("Face is under-exposed"))
        self.assertIs(result.decision, Decision.UNCERTAIN)

    def test_good_images_keep_a_confident_mismatch(self):
        result = verdict(0.02)
        self.assertIs(result.decision, Decision.NO_MATCH)

    def test_liveness_failure_alone_does_not_erase_a_mismatch(self):
        """A spoof attempt that is also the wrong face is still the wrong face."""
        result = verdict(0.02, liveness_live=False, liveness_score=0.1)
        self.assertIs(result.decision, Decision.NO_MATCH)


class TestUncertaintyIsExplained(unittest.TestCase):
    """Being unsure is only useful if the officer is told why."""

    def test_uncertain_result_carries_reasons_and_advice(self):
        result = verdict(0.85, live_quality=poor_quality())
        self.assertTrue(result.reasons)
        self.assertTrue(result.blockers)
        self.assertTrue(result.recapture_advice)
        self.assertIn("cannot say", result.summary)

    def test_mid_band_uncertainty_still_advises(self):
        midpoint = (settings.MATCH_THRESHOLD + settings.MISMATCH_THRESHOLD) / 2.0
        result = verdict(midpoint)
        self.assertTrue(result.recapture_advice)

    def test_confidence_falls_with_quality(self):
        strong = verdict(0.72)
        weak = verdict(0.72, live_quality=QualityResult(score=0.58, usable=True))
        self.assertGreater(strong.confidence, weak.confidence)

    def test_serialisable(self):
        payload = verdict(0.72).to_dict()
        self.assertEqual(payload["decision"], "MATCH")
        self.assertIn("reasons", payload)
        self.assertIn("confidence", payload)


class TestQualityAssessor(unittest.TestCase):
    """The quality gate itself, on synthetic images with known defects."""

    def _face_image(self, size=400, face_px=200, blur=0, brightness=0):
        image = np.full((size, size, 3), 90, dtype=np.uint8)
        centre = size // 2
        half = face_px // 2
        cv2.ellipse(image, (centre, centre), (half, int(half * 1.25)), 0, 0, 360,
                    (185, 155, 135), -1)
        cv2.circle(image, (centre - half // 2, centre - half // 3), half // 8, (40, 40, 45), -1)
        cv2.circle(image, (centre + half // 2, centre - half // 3), half // 8, (40, 40, 45), -1)
        cv2.ellipse(image, (centre, centre + half // 2), (half // 3, half // 8), 0, 0, 180,
                    (90, 70, 70), 3)
        rng = np.random.default_rng(7)
        noise = rng.normal(0, 14, image.shape)
        image = np.clip(image.astype(np.float64) + noise, 0, 255).astype(np.uint8)
        if blur:
            image = cv2.GaussianBlur(image, (blur | 1, blur | 1), 0)
        if brightness:
            image = np.clip(image.astype(np.int16) + brightness, 0, 255).astype(np.uint8)
        return image

    def _face(self, size=400, face_px=200, confidence=0.97):
        centre = size // 2
        half = face_px // 2
        bbox = np.array(
            [centre - half, centre - int(half * 1.25), centre + half, centre + int(half * 1.25)],
            dtype=np.float32,
        )
        landmarks = np.array(
            [
                [centre - half // 2, centre - half // 3],
                [centre + half // 2, centre - half // 3],
                [centre, centre],
                [centre - half // 3, centre + half // 2],
                [centre + half // 3, centre + half // 2],
            ],
            dtype=np.float32,
        )
        return DetectedFace(bbox=bbox, confidence=confidence, landmarks=landmarks)

    def test_good_face_is_usable(self):
        result = face_quality_assessor.assess(self._face_image(), self._face(), source="live")
        self.assertTrue(result.usable, msg=f"issues: {result.issues}")
        self.assertGreater(result.score, settings.QUALITY_MIN_SCORE)

    def test_blurred_face_is_rejected(self):
        result = face_quality_assessor.assess(
            self._face_image(blur=31), self._face(), source="live"
        )
        self.assertFalse(result.usable)
        self.assertTrue(any("blur" in i.lower() for i in result.issues))
        self.assertTrue(result.advice)

    def test_dark_face_is_rejected(self):
        result = face_quality_assessor.assess(
            self._face_image(brightness=-95), self._face(), source="live"
        )
        self.assertFalse(result.usable)

    def test_tiny_face_is_rejected(self):
        result = face_quality_assessor.assess(
            self._face_image(face_px=40), self._face(face_px=40), source="live"
        )
        self.assertFalse(result.usable)
        self.assertTrue(any("px" in i for i in result.issues))

    def test_document_portraits_get_a_lower_resolution_bar(self):
        image = self._face_image(face_px=70)
        face = self._face(face_px=70)
        live = face_quality_assessor.assess(image, face, source="live")
        document = face_quality_assessor.assess(image, face, source="document")
        resolution_live = next(c for c in live.checks if c.name == "resolution")
        resolution_doc = next(c for c in document.checks if c.name == "resolution")
        self.assertFalse(resolution_live.passed)
        self.assertTrue(resolution_doc.passed)

    def test_turned_head_fails_pose(self):
        face = self._face()
        # Push the nose almost onto one eye - a head turned well away from camera.
        face.landmarks[2][0] = face.landmarks[0][0] + 4
        result = face_quality_assessor.assess(self._face_image(), face, source="live")
        pose = next(c for c in result.checks if c.name == "pose")
        self.assertFalse(pose.passed)

    def test_low_detector_confidence_fails(self):
        result = face_quality_assessor.assess(
            self._face_image(), self._face(confidence=0.4), source="live"
        )
        detection = next(c for c in result.checks if c.name == "detection")
        self.assertFalse(detection.passed)

    def test_missing_landmarks_do_not_fail_pose(self):
        face = DetectedFace(
            bbox=np.array([100, 100, 300, 350], dtype=np.float32), confidence=0.95
        )
        result = face_quality_assessor.assess(self._face_image(), face, source="live")
        pose = next(c for c in result.checks if c.name == "pose")
        self.assertTrue(pose.passed)

    def test_serialisable(self):
        payload = face_quality_assessor.assess(
            self._face_image(), self._face(), source="live"
        ).to_dict()
        self.assertIn("score", payload)
        self.assertIn("usable", payload)
        self.assertIsInstance(payload["checks"], list)


class TestThresholdSanity(unittest.TestCase):
    """The configured thresholds have to sit where the comments claim."""

    def test_thresholds_are_on_the_raw_cosine_scale(self):
        self.assertLess(settings.MISMATCH_THRESHOLD, settings.MATCH_THRESHOLD)
        # Above SFace's own break-even point, which is what buys the low FAR.
        self.assertGreater(settings.MATCH_THRESHOLD, 0.363)
        # But not so high that no genuine pair could ever clear it.
        self.assertLess(settings.MATCH_THRESHOLD, 0.75)

    def test_identification_is_stricter_than_verification(self):
        self.assertGreaterEqual(settings.IDENTIFY_THRESHOLD, settings.MATCH_THRESHOLD)


if __name__ == "__main__":
    unittest.main()


class TestYuNetRowLayout(unittest.TestCase):
    """The detector's output layout, pinned.

    YuNet returns ``[x, y, w, h, 10 landmark coords, score]`` - the score is the LAST
    element. Reading index 4 as the confidence instead takes the right eye's
    x-coordinate, and shifts every landmark by one slot. That second effect is the
    dangerous one: alignCrop then warps the face to the wrong canonical position, which
    degrades every embedding and pulls unrelated faces closer together. It is a
    false-accept source that hides in an array index and shows up nowhere except as a
    slightly worse score, so it is pinned here.
    """

    def _row(self):
        # A plausible frontal detection: box, then five landmarks, then the score.
        return np.array(
            [
                100.0, 120.0, 180.0, 240.0,          # x, y, w, h
                150.0, 190.0,                        # right eye
                230.0, 192.0,                        # left eye
                190.0, 240.0,                        # nose
                158.0, 292.0,                        # right mouth corner
                222.0, 294.0,                        # left mouth corner
                0.93,                                # score
            ],
            dtype=np.float32,
        )

    def test_score_is_the_last_element(self):
        from face_detector import FaceDetector

        row = self._row()
        detector = FaceDetector()
        detector._detector = _StubDetector([row])

        face = detector.detect(np.zeros((400, 400, 3), dtype=np.uint8))[0]

        self.assertAlmostEqual(face.confidence, 0.93, places=5)
        # A probability, not a pixel coordinate. This is the assertion that fails if
        # the index regresses.
        self.assertLessEqual(face.confidence, 1.0)
        self.assertGreaterEqual(face.confidence, 0.0)

    def test_landmarks_start_at_index_four(self):
        from face_detector import FaceDetector

        detector = FaceDetector()
        detector._detector = _StubDetector([self._row()])

        face = detector.detect(np.zeros((400, 400, 3), dtype=np.uint8))[0]

        self.assertEqual(face.landmarks.shape, (5, 2))
        np.testing.assert_allclose(face.landmarks[0], [150.0, 190.0])  # right eye
        np.testing.assert_allclose(face.landmarks[1], [230.0, 192.0])  # left eye
        np.testing.assert_allclose(face.landmarks[2], [190.0, 240.0])  # nose

    def test_correct_landmarks_read_as_a_frontal_pose(self):
        """The whole point of the indices: a frontal face must measure as frontal."""
        from face_detector import FaceDetector

        detector = FaceDetector()
        detector._detector = _StubDetector([self._row()])
        face = detector.detect(np.zeros((400, 400, 3), dtype=np.uint8))[0]

        pose = face_quality_assessor._check_pose(face)
        self.assertTrue(pose.passed, msg=pose.reason)

    def test_bounding_box_is_converted_to_corners(self):
        from face_detector import FaceDetector

        detector = FaceDetector()
        detector._detector = _StubDetector([self._row()])
        face = detector.detect(np.zeros((400, 400, 3), dtype=np.uint8))[0]

        np.testing.assert_allclose(face.bbox, [100.0, 120.0, 280.0, 360.0])
        self.assertAlmostEqual(face.width, 180.0)
        self.assertAlmostEqual(face.height, 240.0)


class _StubDetector:
    """Stands in for cv2.FaceDetectorYN so the layout can be tested without a model."""

    def __init__(self, rows):
        self._rows = np.array(rows, dtype=np.float32)

    def setInputSize(self, size):
        pass

    def detect(self, image):
        return 1, self._rows
