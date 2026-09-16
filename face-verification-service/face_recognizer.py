"""Face recognition using OpenCV FaceRecognizerSF (SFace)."""

import logging
import os
from dataclasses import dataclass
from typing import Optional

import cv2
import numpy as np

from config import settings
from face_detector import DetectedFace

logger = logging.getLogger(__name__)

MODEL_DIR = os.path.join(os.path.dirname(__file__), "models")
RECOGNIZER_MODEL = os.path.join(MODEL_DIR, "face_recognizer_fast.onnx")


@dataclass
class EmbeddingResult:
    """Result of embedding extraction."""

    embedding: np.ndarray
    norm: float
    aligned: Optional[np.ndarray] = None
    alignment_used: bool = True


class FaceRecognizer:
    """SFace-based face recognizer using OpenCV FaceRecognizerSF."""

    def __init__(self):
        self._recognizer: Optional[cv2.FaceRecognizerSF] = None

    def _ensure_loaded(self):
        if self._recognizer is not None:
            return

        if not os.path.exists(RECOGNIZER_MODEL):
            raise FileNotFoundError(
                f"Face recognition model not found: {RECOGNIZER_MODEL}. "
                "Download face_recognizer_fast.onnx."
            )

        logger.info("Loading SFace recognizer from %s", RECOGNIZER_MODEL)
        self._recognizer = cv2.FaceRecognizerSF_create(RECOGNIZER_MODEL, "")
        logger.info("SFace recognizer loaded successfully")

    def extract_embedding(self, image: np.ndarray, face: DetectedFace) -> Optional[EmbeddingResult]:
        """Extract a face embedding from a detected face region using SFace.

        Alignment matters more than it looks. SFace is trained on faces warped to
        a canonical 112x112 layout using the five landmarks; feeding it a plain
        bounding-box crop shifts every embedding in the same arbitrary direction,
        which pulls unrelated faces closer together. That is a false-accept
        generator, so an unaligned crop is reported rather than silently used.
        """
        self._ensure_loaded()

        alignment_used = True

        # SFace alignCrop needs the full 15-value YuNet detection output
        # (x, y, w, h, score, 5 landmarks x 2 coords) for proper alignment.
        aligned = None
        if face.raw_detection is not None and len(face.raw_detection) >= 15:
            try:
                aligned = self._recognizer.alignCrop(
                    image, face.raw_detection.reshape(1, -1).astype(np.float32)
                )
            except cv2.error as e:
                logger.warning("alignCrop failed: %s", e)
                aligned = None

        if aligned is None or aligned.size == 0:
            # Fall back to a square, landmark-free crop resized to the model's
            # input. Usable, but measurably less reliable - the caller is told so
            # it can downgrade the decision to "uncertain" rather than trusting it.
            alignment_used = False
            x1, y1, x2, y2 = face.expanded_roi(image.shape, padding=0.2)
            face_crop = image[y1:y2, x1:x2]
            if face_crop.size == 0:
                logger.warning("Empty face crop after expansion")
                return None
            aligned = cv2.resize(
                face_crop,
                (settings.RECOGNITION_INPUT_SIZE, settings.RECOGNITION_INPUT_SIZE),
                interpolation=cv2.INTER_AREA,
            )
            logger.warning("Face alignment unavailable; using an unaligned crop")

        embedding = self._recognizer.feature(aligned)
        if embedding is None:
            logger.warning("SFace feature extraction returned None")
            return None

        embedding = embedding.flatten().astype(np.float64)
        norm = float(np.linalg.norm(embedding))

        if norm == 0.0 or not np.isfinite(norm):
            logger.warning("SFace produced a degenerate embedding (norm %s)", norm)
            return None

        return EmbeddingResult(
            embedding=embedding,
            norm=norm,
            aligned=aligned,
            alignment_used=alignment_used,
        )

    def extract_embedding_from_bytes(
        self, image_bytes: bytes, face: DetectedFace
    ) -> Optional[EmbeddingResult]:
        """Extract embedding from raw image bytes."""
        nparr = np.frombuffer(image_bytes, np.uint8)
        image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
        if image is None:
            raise ValueError("Could not decode image from bytes")
        return self.extract_embedding(image, face)

    @staticmethod
    def cosine_similarity(emb1: np.ndarray, emb2: np.ndarray) -> float:
        """Raw cosine similarity between two embeddings, in [-1, 1].

        This is the decision variable, and it is deliberately NOT rescaled onto
        [0, 1]. An earlier version returned ``(cosine + 1) / 2``, which looks
        harmless and is not: it compresses SFace's entire useful range into
        roughly [0.45, 0.90], so two strangers scoring a genuine 0.10 are
        reported as "55% similar" and land inside the match band. Every
        threshold in this service is calibrated against the raw cosine, where
        SFace's own published break-even point is 0.363.
        """
        if emb1.shape != emb2.shape:
            raise ValueError(f"Embedding dimension mismatch: {emb1.shape} vs {emb2.shape}")

        norm1 = float(np.linalg.norm(emb1))
        norm2 = float(np.linalg.norm(emb2))

        if norm1 == 0 or norm2 == 0:
            return 0.0

        cosine = float(np.dot(emb1, emb2)) / (norm1 * norm2)
        # Guard against floating-point drift just outside the valid range.
        return max(-1.0, min(1.0, cosine))

    @staticmethod
    def l2_distance(emb1: np.ndarray, emb2: np.ndarray) -> float:
        """Compute L2 (Euclidean) distance between two embeddings."""
        return float(np.linalg.norm(emb1 - emb2))

    @staticmethod
    def normalise(embedding: np.ndarray) -> np.ndarray:
        """L2-normalise an embedding so stored vectors compare by dot product."""
        norm = float(np.linalg.norm(embedding))
        if norm == 0:
            return embedding.astype(np.float64)
        return (embedding / norm).astype(np.float64)

    @property
    def is_available(self) -> bool:
        try:
            self._ensure_loaded()
            return True
        except Exception as e:
            logger.warning("Face recognizer unavailable: %s", e)
            return False


face_recognizer = FaceRecognizer()
