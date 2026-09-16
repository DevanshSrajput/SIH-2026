"""Face detection using OpenCV FaceDetectorYN (YuNet)."""

import logging
import os
from dataclasses import dataclass
from typing import Optional

import cv2
import numpy as np

from config import settings

logger = logging.getLogger(__name__)

MODEL_DIR = os.path.join(os.path.dirname(__file__), "models")
YUNET_MODEL = os.path.join(MODEL_DIR, "yunet.onnx")


@dataclass
class DetectedFace:
    """A detected face with bounding box and confidence."""

    bbox: np.ndarray  # [x1, y1, x2, y2]
    confidence: float
    landmarks: Optional[np.ndarray] = None
    raw_detection: Optional[np.ndarray] = None  # Full 15-value YuNet output for alignCrop

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
    """OpenCV FaceDetectorYN (YuNet) face detector."""

    def __init__(self):
        self._detector: Optional[cv2.FaceDetectorYN] = None

    def _ensure_loaded(self):
        if self._detector is not None:
            return

        if not os.path.exists(YUNET_MODEL):
            raise FileNotFoundError(
                f"Face detection model not found: {YUNET_MODEL}. "
                "Download yunet.onnx from the OpenCV Zoo."
            )

        logger.info("Loading YuNet face detector from %s", YUNET_MODEL)
        self._detector = cv2.FaceDetectorYN_create(YUNET_MODEL, "", (0, 0))
        self._detector.setNMSThreshold(0.3)
        self._detector.setScoreThreshold(settings.DETECTION_CONFIDENCE)
        logger.info("YuNet face detector loaded successfully")

    def detect(self, image: np.ndarray) -> list[DetectedFace]:
        """Detect faces in a BGR image using YuNet."""
        if image is None or image.size == 0:
            raise ValueError("Image is empty or invalid")

        self._ensure_loaded()

        h, w = image.shape[:2]
        self._detector.setInputSize((w, h))

        retval, faces = self._detector.detect(image)

        if retval is None or faces is None or len(faces) == 0:
            logger.debug("No faces detected in image")
            return []

        detected = []
        for face_data in faces:
            # YuNet row layout is:
            #   [x, y, w, h,
            #    x_righteye, y_righteye, x_lefteye, y_lefteye, x_nose, y_nose,
            #    x_rightmouth, y_rightmouth, x_leftmouth, y_leftmouth,
            #    score]
            #
            # The score is the LAST element, not index 4. Reading index 4 as the
            # confidence takes the right eye's x-coordinate instead - which is why
            # scores used to come back as 233 rather than 0.7 - and shifts every
            # landmark by one slot. Misaligned landmarks feed alignCrop a face warped
            # to the wrong canonical position, which degrades the embedding and pulls
            # unrelated faces together: a false-accept source hiding in an index.
            x1, y1, w_box, h_box = face_data[:4]
            confidence = float(face_data[-1])
            bbox = np.array([float(x1), float(y1), float(x1 + w_box), float(y1 + h_box)])

            landmarks = None
            if len(face_data) >= 15:
                landmarks = np.array(face_data[4:14]).reshape(5, 2)

            detected.append(DetectedFace(
                bbox=bbox,
                confidence=confidence,
                landmarks=landmarks,
                raw_detection=face_data.astype(np.float32),
            ))

        detected.sort(key=lambda f: f.confidence, reverse=True)

        if len(detected) > settings.MAX_FACES_PER_IMAGE:
            logger.warning(
                "Found %d faces, limiting to %d",
                len(detected),
                settings.MAX_FACES_PER_IMAGE,
            )
            detected = detected[: settings.MAX_FACES_PER_IMAGE]

        logger.debug("Detected %d face(s)", len(detected))
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
