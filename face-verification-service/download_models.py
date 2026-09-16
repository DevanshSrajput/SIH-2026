"""Fetch the face detection and recognition models this service runs on.

    python download_models.py

Models land in ``models/`` next to this file - the directory the service loads from -
not in a user cache. Run it once after cloning; the Docker build runs it too, so the
image ships with the models inside it and never reaches the network at runtime. A
border post is exactly the deployment that cannot assume outbound internet.

Files already present are verified and left alone, so re-running this is cheap and
safe.

The models are YuNet (detection) and SFace (recognition), both from the OpenCV Zoo.
An earlier version of this script downloaded RetinaFace and ArcFace through
insightface; the service no longer uses either, and insightface is not a dependency.
"""

import hashlib
import logging
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

MODEL_DIR = Path(__file__).parent / "models"

ZOO = "https://github.com/opencv/opencv_zoo/raw/main/models"


@dataclass
class Model:
    """One model file, with somewhere to get it and a way to tell it is intact."""

    filename: str
    urls: list[str]
    minimum_bytes: int
    purpose: str

    @property
    def path(self) -> Path:
        return MODEL_DIR / self.filename


MODELS = [
    Model(
        filename="yunet.onnx",
        urls=[
            f"{ZOO}/face_detection_yunet/face_detection_yunet_2023mar.onnx",
            "https://raw.githubusercontent.com/opencv/opencv_zoo/main/models/"
            "face_detection_yunet/face_detection_yunet_2023mar.onnx",
        ],
        minimum_bytes=180_000,
        purpose="face detection (YuNet)",
    ),
    Model(
        filename="face_recognizer_fast.onnx",
        urls=[
            f"{ZOO}/face_recognition_sface/face_recognition_sface_2021dec.onnx",
            "https://raw.githubusercontent.com/opencv/opencv_zoo/main/models/"
            "face_recognition_sface/face_recognition_sface_2021dec.onnx",
        ],
        minimum_bytes=30_000_000,
        purpose="face recognition (SFace)",
    ),
]


def fetch(model: Model) -> bool:
    """Download one model, trying each mirror in turn."""
    MODEL_DIR.mkdir(parents=True, exist_ok=True)

    for url in model.urls:
        logger.info("Downloading %s from %s", model.filename, url)
        temporary = model.path.with_suffix(model.path.suffix + ".part")
        try:
            with urllib.request.urlopen(url, timeout=120) as response:
                temporary.write_bytes(response.read())
        except (urllib.error.URLError, TimeoutError, OSError) as e:
            logger.warning("  failed: %s", e)
            temporary.unlink(missing_ok=True)
            continue

        size = temporary.stat().st_size
        if size < model.minimum_bytes:
            # A proxy error page or an HTML 404 saved under a .onnx name is the usual
            # cause, and it would otherwise fail much later as an opaque load error.
            logger.warning(
                "  rejected: %s is %d bytes, below the %d minimum - not a model file",
                model.filename, size, model.minimum_bytes,
            )
            temporary.unlink(missing_ok=True)
            continue

        # Written to a .part file and moved into place only once complete, so an
        # interrupted download never leaves a truncated model that loads and then
        # silently produces wrong embeddings.
        temporary.replace(model.path)
        logger.info("  saved %s (%.1f MB)", model.filename, size / 1e6)
        return True

    return False


def verify(model: Model) -> bool:
    """Check that a present file is plausibly the model, and that OpenCV can load it."""
    if not model.path.exists():
        return False

    size = model.path.stat().st_size
    if size < model.minimum_bytes:
        logger.warning(
            "%s is only %d bytes - it looks truncated and will be re-downloaded",
            model.filename, size,
        )
        return False

    digest = hashlib.sha256(model.path.read_bytes()).hexdigest()
    logger.info("%s present (%.1f MB, sha256 %s...)", model.filename, size / 1e6, digest[:12])
    return True


def load_check() -> bool:
    """Load both models through OpenCV, which is the only real proof they work."""
    try:
        import cv2
    except ImportError:
        logger.warning("OpenCV is not installed; skipping the load check")
        return True

    try:
        detector = cv2.FaceDetectorYN_create(str(MODEL_DIR / "yunet.onnx"), "", (320, 320))
        recognizer = cv2.FaceRecognizerSF_create(
            str(MODEL_DIR / "face_recognizer_fast.onnx"), ""
        )
    except cv2.error as e:
        logger.error("A model is present but OpenCV could not load it: %s", e)
        return False

    logger.info("Both models loaded successfully (%s, %s)",
                type(detector).__name__, type(recognizer).__name__)
    return True


def main() -> int:
    logger.info("Model directory: %s", MODEL_DIR)

    missing = []
    for model in MODELS:
        if verify(model):
            continue
        if not fetch(model):
            missing.append(model)

    if missing:
        logger.error("")
        logger.error("Could not obtain %d model(s):", len(missing))
        for model in missing:
            logger.error("  %s - %s", model.filename, model.purpose)
            logger.error("    try: %s", model.urls[0])
        logger.error("")
        logger.error("Download them by hand into %s and re-run this script.", MODEL_DIR)
        return 1

    if not load_check():
        return 1

    logger.info("All models ready.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
