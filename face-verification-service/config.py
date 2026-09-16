"""Configuration for the Face Verification Service."""

import os
from pathlib import Path


class Settings:
    """Service configuration loaded from environment variables with sensible defaults."""

    HOST: str = os.getenv("FACE_SERVICE_HOST", "0.0.0.0")
    PORT: int = int(os.getenv("FACE_SERVICE_PORT", "5000"))
    DEBUG: str = os.getenv("FACE_SERVICE_DEBUG", "false").lower() == "true"

    # Model settings
    MODEL_DIR: Path = Path(os.getenv("FACE_MODELS_DIR", str(Path.home() / ".cache" / "face-verification-models")))
    FACE_DETECTOR_MODEL: str = os.getenv("FACE_DETECTOR_MODEL", "retinaface_mobile")
    FACE_RECOGNIZER_MODEL: str = os.getenv("FACE_RECOGNIZER_MODEL", "arcface_r100_v1")

    # Detection thresholds
    DETECTION_CONFIDENCE: float = float(os.getenv("DETECTION_CONFIDENCE", "0.5"))
    MAX_FACES_PER_IMAGE: int = int(os.getenv("MAX_FACES_PER_IMAGE", "5"))

    # Matching thresholds
    MATCH_THRESHOLD: float = float(os.getenv("MATCH_THRESHOLD", "0.75"))
    MISMATCH_THRESHOLD: float = float(os.getenv("MISMATCH_THRESHOLD", "0.55"))

    # Liveness thresholds
    LIVENESS_THRESHOLD: float = float(os.getenv("LIVENESS_THRESHOLD", "0.5"))
    LIVENESS_TEXTURE_MIN_VARIANCE: float = float(os.getenv("LIVENESS_TEXTURE_MIN_VARIANCE", "25.0"))
    LIVENESS_MOTION_FRAMES: int = int(os.getenv("LIVENESS_MOTION_FRAMES", "5"))

    # Image settings
    MAX_IMAGE_SIZE_MB: int = int(os.getenv("MAX_IMAGE_SIZE_MB", "20"))
    RECOGNITION_INPUT_SIZE: int = 112

    # Service settings
    REQUEST_TIMEOUT_SECONDS: int = int(os.getenv("REQUEST_TIMEOUT_SECONDS", "30"))
    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")


settings = Settings()
