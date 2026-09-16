"""Download required face detection and recognition models.

Run this script before starting the service to pre-download models:
    python download_models.py

Models are downloaded to ~/.cache/face-verification-models/ by default.
"""

import sys
import logging
from pathlib import Path

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)


def download_models():
    """Download RetinaFace and ArcFace models via insightface."""
    model_dir = Path.home() / ".cache" / "face-verification-models"
    model_dir.mkdir(parents=True, exist_ok=True)

    logger.info("Model directory: %s", model_dir)

    try:
        from insightface.app import FaceAnalysis

        logger.info("Downloading RetinaFace mobile model...")
        app = FaceAnalysis(
            name="retinaface_mobile",
            root=str(model_dir),
            providers=["CPUExecutionProvider"],
        )
        app.prepare(ctx_id=0, det_size=(640, 640))
        logger.info("RetinaFace mobile model ready")
    except Exception as e:
        logger.error("Failed to download RetinaFace model: %s", e)
        sys.exit(1)

    try:
        logger.info("Downloading ArcFace R100 model...")
        app2 = FaceAnalysis(
            name="arcface_r100_v1",
            root=str(model_dir),
            providers=["CPUExecutionProvider"],
        )
        app2.prepare(ctx_id=0, det_size=(640, 640))
        logger.info("ArcFace R100 model ready")
    except Exception as e:
        logger.error("Failed to download ArcFace model: %s", e)
        sys.exit(1)

    logger.info("All models downloaded successfully to %s", model_dir)


if __name__ == "__main__":
    download_models()
