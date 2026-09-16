"""Face detection using RetinaFace (insightface)."""

import logging
from dataclasses import dataclass
from typing import Optional

import cv2
import numpy as np
from insightface.app import FaceAnalysis

from config import settings

logger = logging.getLogger(__name__)


@dataclass
class DetectedFace:
    """A detected face with bounding box and confidence."""

    bbox: np.ndarray  # [x1, y1, x2, y2]
    confidence: float
    landmarks: Optional[np.ndarray] = None  # 5 facial landmarks

    @property
    def width(self) -> float:
        return self.bbox[2] - self.bbox[0]

    @property
    def height(self) -> float:
        return self.bbox[3] - self.bbox[1]

    @property
    def center(self) -> tuple[float, float]:
        return (
            (self.bbox[0] + self.bbox[2]) / 2,
            (self.bbox[1] + self.bbox[3]) / 2,
        )

    def expanded_roi(self, image_shape: tuple, padding: float = 0.15) -> tuple[int, int, int, int]:
        """Return expanded bounding box coordinates clamped to image bounds."""
        h, w = image_shape[:2]
        pad_x = self.width * padding
        pad_y = self.height * padding
        x1 = max(0, int(self.bbox[0] - pad_x))
        y1 = max(0, int(self.bbox[1] - pad_y))
        x2 = min(w, int(self.bbox[2] + pad_x))
        y2 = min(h, int(self.bbox[3] + pad_y))
        return x1, y1, x2, y2


class FaceDetector:
    """RetinaFace-based face detector with automatic model management."""

    def __init__(self):
        self._app: Optional[FaceAnalysis] = None
        self._loaded = False

    def _ensure_loaded(self) -> FaceAnalysis:
        if self._app is None:
            logger.info("Loading RetinaFace detector (model: %s)", settings.FACE_DETECTOR_MODEL)
            self._app = FaceAnalysis(
                name=settings.FACE_DETECTOR_MODEL,
                root=str(settings.MODEL_DIR),
                providers=["CPUExecutionProvider"],
            )
            self._app.prepare(ctx_id=0, det_size=(640, 640))
            self._loaded = True
            logger.info("RetinaFace detector loaded successfully")
        return self._app

    def detect(self, image: np.ndarray) -> list[DetectedFace]:
        """
        Detect faces in an image.

        Args:
            image: BGR image (OpenCV format).

        Returns:
            List of DetectedFace objects, sorted by confidence (highest first).

        Raises:
            ValueError: If image is invalid or empty.
        """
        if image is None or image.size == 0:
            raise ValueError("Image is empty or invalid")

        app = self._ensure_loaded()
        faces = app.get(image)

        if not faces:
            logger.debug("No faces detected in image")
            return []

        detected = []
        for face in faces:
            bbox = face.bbox.astype(np.float32)
            confidence = float(face.det_score)

            if confidence < settings.DETECTION_CONFIDENCE:
                continue

            landmarks = face.kps if hasattr(face, "kps") else None
            detected.append(DetectedFace(bbox=bbox, confidence=confidence, landmarks=landmarks))

        detected.sort(key=lambda f: f.confidence, reverse=True)

        if len(detected) > settings.MAX_FACES_PER_IMAGE:
            logger.warning(
                "Found %d faces, limiting to %d",
                len(detected),
                settings.MAX_FACES_PER_IMAGE,
            )
            detected = detected[: settings.MAX_FACES_PER_IMAGE]

        logger.debug("Detected %d face(s) with confidence >= %.2f", len(detected), settings.DETECTION_CONFIDENCE)
        return detected

    def detect_from_bytes(self, image_bytes: bytes) -> list[DetectedFace]:
        """Detect faces from raw image bytes."""
        nparr = np.frombuffer(image_bytes, np.uint8)
        image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
        if image is None:
            raise ValueError("Could not decode image from bytes")
        return self.detect(image)

    @property
    def is_available(self) -> bool:
        try:
            self._ensure_loaded()
            return True
        except Exception as e:
            logger.warning("Face detector unavailable: %s", e)
            return False


face_detector = FaceDetector()
