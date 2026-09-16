"""Face recognition using ArcFace embeddings and cosine similarity."""

import logging
from dataclasses import dataclass
from typing import Optional

import cv2
import numpy as np
from insightface.app import FaceAnalysis

from config import settings
from face_detector import DetectedFace

logger = logging.getLogger(__name__)


@dataclass
class EmbeddingResult:
    """Result of embedding extraction."""

    embedding: np.ndarray
    norm: float


class FaceRecognizer:
    """ArcFace-based face recognizer for generating 512-dim embeddings."""

    def __init__(self):
        self._app: Optional[FaceAnalysis] = None

    def _ensure_loaded(self) -> FaceAnalysis:
        if self._app is None:
            logger.info("Loading ArcFace recognizer (model: %s)", settings.FACE_RECOGNIZER_MODEL)
            self._app = FaceAnalysis(
                name=settings.FACE_RECOGNIZER_MODEL,
                root=str(settings.MODEL_DIR),
                providers=["CPUExecutionProvider"],
            )
            self._app.prepare(ctx_id=0, det_size=(640, 640))
            logger.info("ArcFace recognizer loaded successfully")
        return self._app

    def extract_embedding(self, image: np.ndarray, face: DetectedFace) -> Optional[EmbeddingResult]:
        """
        Extract a face embedding from a detected face region.

        Uses the ArcFace recognition model directly on the face crop.
        The crop is resized to the model's expected input size (112x112),
        normalized, and fed through the recognition network.

        Args:
            image: Full BGR image.
            face: DetectedFace with bounding box.

        Returns:
            EmbeddingResult with the 512-dim embedding, or None if extraction fails.
        """
        app = self._ensure_loaded()

        x1, y1, x2, y2 = face.expanded_roi(image.shape, padding=0.2)
        face_crop = image[y1:y2, x1:x2]

        if face_crop.size == 0:
            logger.warning("Empty face crop after expansion")
            return None

        resized = cv2.resize(face_crop, (settings.RECOGNITION_INPUT_SIZE, settings.RECOGNITION_INPUT_SIZE))

        # Normalize pixel values to [-1, 1] as expected by ArcFace
        blob = cv2.dnn.blobFromImage(
            resized,
            1.0 / 127.5,
            (settings.RECOGNITION_INPUT_SIZE, settings.RECOGNITION_INPUT_SIZE),
            (127.5, 127.5, 127.5),
        )

        recognizer = app.models.get("recognition")
        if recognizer is None:
            logger.error("Recognition model not loaded")
            return None

        # Run the recognition model forward pass
        output = recognizer.run(blob)
        if output is None or len(output) == 0:
            logger.warning("Recognition model returned empty output")
            return None

        embedding = output[0].flatten().astype(np.float64)

        # L2-normalize the embedding
        norm = float(np.linalg.norm(embedding))
        if norm > 0:
            embedding = embedding / norm

        return EmbeddingResult(embedding=embedding, norm=norm)

    def extract_embedding_from_bytes(self, image_bytes: bytes, face: DetectedFace) -> Optional[EmbeddingResult]:
        """Extract embedding from raw image bytes."""
        nparr = np.frombuffer(image_bytes, np.uint8)
        image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
        if image is None:
            raise ValueError("Could not decode image from bytes")
        return self.extract_embedding(image, face)

    @staticmethod
    def cosine_similarity(emb1: np.ndarray, emb2: np.ndarray) -> float:
        """
        Compute cosine similarity between two embeddings.

        Maps result from [-1, 1] to [0, 1] where 1.0 = identical faces.
        """
        if emb1.shape != emb2.shape:
            raise ValueError(f"Embedding dimension mismatch: {emb1.shape} vs {emb2.shape}")

        dot = float(np.dot(emb1, emb2))
        norm1 = float(np.linalg.norm(emb1))
        norm2 = float(np.linalg.norm(emb2))

        if norm1 == 0 or norm2 == 0:
            return 0.0

        cosine = dot / (norm1 * norm2)
        # Map from [-1, 1] to [0, 1]
        return max(0.0, min(1.0, (cosine + 1.0) / 2.0))

    @staticmethod
    def l2_distance(emb1: np.ndarray, emb2: np.ndarray) -> float:
        """Compute L2 (Euclidean) distance between two embeddings."""
        return float(np.linalg.norm(emb1 - emb2))

    @property
    def is_available(self) -> bool:
        try:
            self._ensure_loaded()
            return True
        except Exception as e:
            logger.warning("Face recognizer unavailable: %s", e)
            return False


face_recognizer = FaceRecognizer()
