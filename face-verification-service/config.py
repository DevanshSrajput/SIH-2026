"""Configuration for the Face Verification Service."""

import os
from pathlib import Path


def _flag(name: str, default: str = "false") -> bool:
    return os.getenv(name, default).strip().lower() in {"1", "true", "yes", "on"}


class Settings:
    """Service configuration loaded from environment variables with sensible defaults."""

    HOST: str = os.getenv("FACE_SERVICE_HOST", "0.0.0.0")
    PORT: int = int(os.getenv("FACE_SERVICE_PORT", "5000"))
    DEBUG: bool = _flag("FACE_SERVICE_DEBUG")

    # Model settings
    MODEL_DIR: Path = Path(os.getenv("FACE_MODELS_DIR", str(Path(__file__).parent / "models")))
    FACE_DETECTOR_MODEL: str = os.getenv("FACE_DETECTOR_MODEL", "yunet")
    FACE_RECOGNIZER_MODEL: str = os.getenv("FACE_RECOGNIZER_MODEL", "sface")

    # Detection thresholds
    DETECTION_CONFIDENCE: float = float(os.getenv("DETECTION_CONFIDENCE", "0.6"))
    MAX_FACES_PER_IMAGE: int = int(os.getenv("MAX_FACES_PER_IMAGE", "5"))
    # A detection this weak is not trusted enough to decide an identity on.
    MIN_DECISION_CONFIDENCE: float = float(os.getenv("MIN_DECISION_CONFIDENCE", "0.80"))

    # ------------------------------------------------------------------
    # Matching thresholds.
    #
    # These are RAW COSINE similarities between SFace embeddings, in [-1, 1].
    # They are NOT a rescaled [0, 1] "percentage": SFace's own published
    # acceptance threshold is cosine 0.363, and rescaling the range destroys the
    # separation between the two populations. Two different people typically
    # score 0.00-0.25; the same person across a document scan and a live capture
    # typically scores 0.40-0.75.
    #
    # MATCH_THRESHOLD sits deliberately ABOVE SFace's 0.363 break-even point.
    # At a border checkpoint a false accept waves an impostor through, so the
    # cost of the two errors is not symmetric: we would rather send a genuine
    # traveller to an officer for a visual check than accept a stranger.
    # ------------------------------------------------------------------
    MATCH_THRESHOLD: float = float(os.getenv("MATCH_THRESHOLD", "0.46"))
    MISMATCH_THRESHOLD: float = float(os.getenv("MISMATCH_THRESHOLD", "0.28"))
    # A score this close to either threshold is reported as uncertain even when
    # it technically falls on the decisive side of it.
    UNCERTAIN_MARGIN: float = float(os.getenv("UNCERTAIN_MARGIN", "0.04"))

    # ------------------------------------------------------------------
    # Face quality gates. A blurred, dark or badly angled face produces an
    # embedding that is unreliable in BOTH directions, so a poor-quality pair is
    # never decided - it is returned as uncertain with a reason to re-capture.
    # ------------------------------------------------------------------
    QUALITY_MIN_SCORE: float = float(os.getenv("QUALITY_MIN_SCORE", "0.55"))
    QUALITY_MIN_SHARPNESS: float = float(os.getenv("QUALITY_MIN_SHARPNESS", "45.0"))
    QUALITY_MIN_BRIGHTNESS: float = float(os.getenv("QUALITY_MIN_BRIGHTNESS", "55.0"))
    QUALITY_MAX_BRIGHTNESS: float = float(os.getenv("QUALITY_MAX_BRIGHTNESS", "205.0"))
    QUALITY_MIN_CONTRAST: float = float(os.getenv("QUALITY_MIN_CONTRAST", "22.0"))
    # Minimum face size in pixels (shortest side of the detection box).
    QUALITY_MIN_FACE_PX_LIVE: int = int(os.getenv("QUALITY_MIN_FACE_PX_LIVE", "90"))
    # Document portraits are printed small and scanned; the bar has to be lower.
    QUALITY_MIN_FACE_PX_DOCUMENT: int = int(os.getenv("QUALITY_MIN_FACE_PX_DOCUMENT", "60"))
    # Head rotation limits, in degrees, derived from the five landmarks.
    QUALITY_MAX_ROLL_DEGREES: float = float(os.getenv("QUALITY_MAX_ROLL_DEGREES", "22.0"))
    QUALITY_MAX_YAW_RATIO: float = float(os.getenv("QUALITY_MAX_YAW_RATIO", "0.34"))

    # Liveness thresholds
    LIVENESS_ENABLED: bool = _flag("LIVENESS_ENABLED", "true")
    LIVENESS_THRESHOLD: float = float(os.getenv("LIVENESS_THRESHOLD", "0.5"))
    LIVENESS_TEXTURE_MIN_VARIANCE: float = float(os.getenv("LIVENESS_TEXTURE_MIN_VARIANCE", "25.0"))
    LIVENESS_MOTION_FRAMES: int = int(os.getenv("LIVENESS_MOTION_FRAMES", "5"))

    # Image settings
    MAX_IMAGE_SIZE_MB: int = int(os.getenv("MAX_IMAGE_SIZE_MB", "20"))
    RECOGNITION_INPUT_SIZE: int = 112

    # Enrolment store (1:N identification against known travellers).
    ENROLMENT_DB: Path = Path(
        os.getenv("FACE_ENROLMENT_DB", str(Path(__file__).parent / "data" / "enrolments.json"))
    )
    # 1:N is a harder problem than 1:1 - more candidates means more chances for a
    # coincidental high score - so identification uses a stricter bar than verification.
    IDENTIFY_THRESHOLD: float = float(os.getenv("IDENTIFY_THRESHOLD", "0.52"))
    # The best candidate must beat the runner-up by this margin to be named.
    IDENTIFY_MARGIN: float = float(os.getenv("IDENTIFY_MARGIN", "0.06"))

    # Service settings
    REQUEST_TIMEOUT_SECONDS: int = int(os.getenv("REQUEST_TIMEOUT_SECONDS", "30"))
    LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO")


settings = Settings()
