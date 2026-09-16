"""Unit tests for the face verification pipeline.

Tests individual components without requiring GPU or model downloads.
Uses synthetic images to validate detection, recognition, and liveness logic.
"""

import sys
import unittest
from pathlib import Path
from unittest.mock import MagicMock, patch

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).parent.parent))


class TestLivenessDetector(unittest.TestCase):
    """Test liveness detection heuristics."""

    def setUp(self):
        from liveness_detector import LivenessDetector, DetectedFace

        self.detector = LivenessDetector()
        self.DetectedFace = DetectedFace

    def _make_face_image(self, size=200, noise_level=30, saturation=100):
        """Create a synthetic face-like image."""
        img = np.zeros((size, size, 3), dtype=np.uint8)
        # Draw a face-like ellipse
        cv2.ellipse(img, (size // 2, size // 2), (size // 3, size // 4), 0, 0, 360, (180, 150, 130), -1)
        # Add eyes
        cv2.circle(img, (size // 3, size // 3), size // 10, (50, 50, 50), -1)
        cv2.circle(img, (2 * size // 3, size // 3), size // 10, (50, 50, 50), -1)
        # Add noise for texture
        noise = np.random.normal(0, noise_level, img.shape).astype(np.int16)
        img = np.clip(img.astype(np.int16) + noise, 0, 255).astype(np.uint8)
        # Adjust saturation
        hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
        hsv[:, :, 1] = np.clip(hsv[:, :, 1].astype(np.int16) + saturation - 100, 0, 255).astype(np.uint8)
        img = cv2.cvtColor(hsv, cv2.COLOR_HSV2BGR)
        return img

    def _make_spoof_image(self, size=200):
        """Create a flat, low-variance image simulating a printed photo."""
        img = np.ones((size, size, 3), dtype=np.uint8) * 128
        # Add very subtle texture
        noise = np.random.normal(0, 5, img.shape).astype(np.int16)
        img = np.clip(img.astype(np.int16) + noise, 0, 255).astype(np.uint8)
        return img

    def test_texture_variance_real_face(self):
        """Real face images should have higher texture variance."""
        from liveness_detector import LivenessCheck

        img = self._make_face_image(noise_level=30)
        face = self.DetectedFace(
            bbox=np.array([10, 10, 190, 190], dtype=np.float32),
            confidence=0.99,
        )
        result = self.detector._check_texture_variance(img[10:190, 10:190])
        self.assertGreater(result.score, 0.3, "Real face should have reasonable texture variance")

    def test_texture_variance_spoof(self):
        """Flat/spoof images should have lower texture variance."""
        img = self._make_spoof_image()
        face = self.DetectedFace(
            bbox=np.array([0, 0, 200, 200], dtype=np.float32),
            confidence=0.99,
        )
        result = self.detector._check_texture_variance(img)
        self.assertLess(result.score, 0.8, "Spoof image should have lower texture score")

    def test_face_size_ratio_normal(self):
        """A face occupying ~15% of the image should pass."""
        img = np.zeros((480, 640, 3), dtype=np.uint8)
        face = self.DetectedFace(
            bbox=np.array([160, 120, 480, 360], dtype=np.float32),
            confidence=0.99,
        )
        result = self.detector._check_face_size_ratio(face, img.shape)
        self.assertTrue(result.passed, "Normal face size should pass")

    def test_face_size_ratio_too_small(self):
        """A very small face should fail."""
        img = np.zeros((480, 640, 3), dtype=np.uint8)
        face = self.DetectedFace(
            bbox=np.array([310, 235, 330, 245], dtype=np.float32),
            confidence=0.99,
        )
        result = self.detector._check_face_size_ratio(face, img.shape)
        self.assertFalse(result.passed, "Very small face should fail")

    def test_face_size_ratio_too_large(self):
        """A face filling most of the image should fail."""
        img = np.zeros((480, 640, 3), dtype=np.uint8)
        face = self.DetectedFace(
            bbox=np.array([10, 10, 630, 470], dtype=np.float32),
            confidence=0.99,
        )
        result = self.detector._check_face_size_ratio(face, img.shape)
        self.assertFalse(result.passed, "Face filling frame should fail")

    def test_full_liveness_check_real(self):
        """Full liveness check on a realistic face image."""
        img = self._make_face_image(noise_level=25, saturation=100)
        face = self.DetectedFace(
            bbox=np.array([10, 10, 190, 190], dtype=np.float32),
            confidence=0.99,
        )
        result = self.detector.check_liveness(img, face)
        self.assertIsInstance(result.score, float)
        self.assertGreaterEqual(result.score, 0.0)
        self.assertLessEqual(result.score, 1.0)
        self.assertEqual(len(result.checks), 6)

    def test_full_liveness_check_empty_region(self):
        """Empty face region should return zero score."""
        img = np.zeros((10, 10, 3), dtype=np.uint8)
        face = self.DetectedFace(
            bbox=np.array([5, 5, 5, 5], dtype=np.float32),
            confidence=0.99,
        )
        result = self.detector.check_liveness(img, face)
        self.assertEqual(result.score, 0.0)
        self.assertFalse(result.is_live)


class TestFaceRecognizer(unittest.TestCase):
    """Test face recognition utilities."""

    def test_cosine_similarity_identical(self):
        """Identical embeddings should have similarity close to 1.0."""
        from face_recognizer import FaceRecognizer

        emb = np.random.randn(512).astype(np.float64)
        emb = emb / np.linalg.norm(emb)
        sim = FaceRecognizer.cosine_similarity(emb, emb)
        self.assertAlmostEqual(sim, 1.0, places=4)

    def test_cosine_similarity_orthogonal(self):
        """Orthogonal embeddings should have similarity around 0.5."""
        from face_recognizer import FaceRecognizer

        emb1 = np.zeros(512, dtype=np.float64)
        emb1[0] = 1.0
        emb2 = np.zeros(512, dtype=np.float64)
        emb2[1] = 1.0
        sim = FaceRecognizer.cosine_similarity(emb1, emb2)
        self.assertAlmostEqual(sim, 0.5, places=4)

    def test_cosine_similarity_opposite(self):
        """Opposite embeddings should have similarity close to 0.0."""
        from face_recognizer import FaceRecognizer

        emb1 = np.zeros(512, dtype=np.float64)
        emb1[0] = 1.0
        emb2 = np.zeros(512, dtype=np.float64)
        emb2[0] = -1.0
        sim = FaceRecognizer.cosine_similarity(emb1, emb2)
        self.assertAlmostEqual(sim, 0.0, places=4)

    def test_cosine_similarity_dimension_mismatch(self):
        """Mismatched dimensions should raise ValueError."""
        from face_recognizer import FaceRecognizer

        emb1 = np.random.randn(512)
        emb2 = np.random.randn(256)
        with self.assertRaises(ValueError):
            FaceRecognizer.cosine_similarity(emb1, emb2)

    def test_l2_distance_identical(self):
        """Identical embeddings should have zero distance."""
        from face_recognizer import FaceRecognizer

        emb = np.random.randn(512).astype(np.float64)
        dist = FaceRecognizer.l2_distance(emb, emb)
        self.assertAlmostEqual(dist, 0.0, places=6)

    def test_l2_distance_different(self):
        """Different embeddings should have positive distance."""
        from face_recognizer import FaceRecognizer

        emb1 = np.random.randn(512)
        emb2 = np.random.randn(512)
        dist = FaceRecognizer.l2_distance(emb1, emb2)
        self.assertGreater(dist, 0.0)


class TestDetectedFace(unittest.TestCase):
    """Test DetectedFace dataclass methods."""

    def test_expanded_roi(self):
        """Expanded ROI should be larger than original and clamped to image bounds."""
        from face_detector import DetectedFace

        face = DetectedFace(bbox=np.array([100, 100, 200, 200], dtype=np.float32), confidence=0.99)
        x1, y1, x2, y2 = face.expanded_roi((480, 640, 3), padding=0.15)
        self.assertLess(x1, 100)
        self.assertLess(y1, 100)
        self.assertGreater(x2, 200)
        self.assertGreater(y2, 200)

    def test_expanded_roi_clamped(self):
        """Expanded ROI should not exceed image boundaries."""
        from face_detector import DetectedFace

        face = DetectedFace(bbox=np.array([0, 0, 50, 50], dtype=np.float32), confidence=0.99)
        x1, y1, x2, y2 = face.expanded_roi((480, 640, 3), padding=0.15)
        self.assertGreaterEqual(x1, 0)
        self.assertGreaterEqual(y1, 0)
        self.assertLessEqual(x2, 640)
        self.assertLessEqual(y2, 480)

    def test_width_height_center(self):
        """Properties should compute correctly."""
        from face_detector import DetectedFace

        face = DetectedFace(bbox=np.array([10, 20, 110, 170], dtype=np.float32), confidence=0.99)
        self.assertAlmostEqual(face.width, 100.0)
        self.assertAlmostEqual(face.height, 150.0)
        cx, cy = face.center
        self.assertAlmostEqual(cx, 60.0)
        self.assertAlmostEqual(cy, 95.0)


class TestConfig(unittest.TestCase):
    """Test configuration defaults."""

    def test_settings_defaults(self):
        """Settings should have sensible defaults."""
        from config import settings

        self.assertEqual(settings.PORT, 5000)
        self.assertGreater(settings.MATCH_THRESHOLD, 0.0)
        self.assertLess(settings.MATCH_THRESHOLD, 1.0)
        self.assertGreater(settings.MISMATCH_THRESHOLD, 0.0)
        self.assertLess(settings.MISMATCH_THRESHOLD, settings.MATCH_THRESHOLD)
        self.assertGreater(settings.LIVENESS_THRESHOLD, 0.0)
        self.assertLess(settings.LIVENESS_THRESHOLD, 1.0)


class TestAppHealth(unittest.TestCase):
    """Test FastAPI health endpoint."""

    def test_health_endpoint(self):
        """Health endpoint should return status ok."""
        from fastapi.testclient import TestClient

        from app import app

        client = TestClient(app)
        response = client.get("/health")
        self.assertEqual(response.status_code, 200)
        data = response.json()
        self.assertEqual(data["status"], "ok")


class TestAppCompareValidation(unittest.TestCase):
    """Test /compare endpoint input validation."""

    def setUp(self):
        from fastapi.testclient import TestClient

        from app import app

        self.client = TestClient(app)

    def test_missing_document(self):
        """Should return 400 when document is missing."""
        response = self.client.post(
            "/compare",
            files={"live": ("test.jpg", b"fake-image-data", "image/jpeg")},
        )
        self.assertEqual(response.status_code, 422)

    def test_missing_live(self):
        """Should return 400 when live capture is missing."""
        response = self.client.post(
            "/compare",
            files={"document": ("test.jpg", b"fake-image-data", "image/jpeg")},
        )
        self.assertEqual(response.status_code, 422)

    def test_empty_document(self):
        """Should return 400 for empty document."""
        response = self.client.post(
            "/compare",
            files={
                "document": ("test.jpg", b"", "image/jpeg"),
                "live": ("live.jpg", b"fake-image-data", "image/jpeg"),
            },
        )
        self.assertEqual(response.status_code, 400)

    def test_invalid_image(self):
        """Should return 400 for unreadable image data."""
        response = self.client.post(
            "/compare",
            files={
                "document": ("test.jpg", b"not-an-image", "image/jpeg"),
                "live": ("live.jpg", b"not-an-image", "image/jpeg"),
            },
        )
        self.assertEqual(response.status_code, 400)


if __name__ == "__main__":
    unittest.main()
