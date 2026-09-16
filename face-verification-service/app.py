"""Face Verification Service - FastAPI Application.

Provides face comparison between a document portrait and a live camera capture,
with liveness/anti-spoofing detection. Designed to be called by the Java backend's
HttpFaceVerifier via POST /compare.
"""

import io
import logging
import time
from contextlib import asynccontextmanager

import cv2
import numpy as np
import uvicorn
from fastapi import FastAPI, File, HTTPException, UploadFile
from fastapi.responses import JSONResponse

from config import settings
from face_detector import face_detector
from face_recognizer import face_recognizer
from liveness_detector import liveness_detector

logging.basicConfig(
    level=getattr(logging, settings.LOG_LEVEL),
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Load models on startup."""
    logger.info("Starting Face Verification Service")
    logger.info("Model directory: %s", settings.MODEL_DIR)
    try:
        face_detector._ensure_loaded()
        face_recognizer._ensure_loaded()
        logger.info("All models loaded successfully")
    except Exception as e:
        logger.error("Failed to load models: %s", e)
    yield
    logger.info("Shutting down Face Verification Service")


app = FastAPI(
    title="Face Verification Service",
    description=(
        "Compares a document portrait against a live camera capture. "
        "Returns similarity score, liveness check, and face detection results."
    ),
    version="1.0.0",
    lifespan=lifespan,
)


def _decode_image(image_bytes: bytes) -> np.ndarray:
    """Decode image bytes to BGR numpy array."""
    nparr = np.frombuffer(image_bytes, np.uint8)
    image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError("Could not decode image")
    return image


@app.get("/health")
async def health():
    """Health check endpoint."""
    return {
        "status": "ok",
        "detector_available": face_detector.is_available,
        "recognizer_available": face_recognizer.is_available,
    }


@app.post("/compare")
async def compare_faces(
    document: UploadFile = File(..., description="Document image containing portrait photo"),
    live: UploadFile = File(..., description="Live camera capture of person"),
):
    """
    Compare a document portrait against a live capture.

    Expected by Java backend's HttpFaceVerifier as POST {service-url}/compare
    with multipart form data containing 'document' and 'live' parts.

    Returns:
        similarity: 0.0-1.0 (1.0 = identical face)
        documentFaceFound: whether a face was detected on the document
        liveFaceFound: whether a face was detected in the live capture
        livenessScore: 0.0-1.0 (1.0 = definitely live)
        livenessLive: whether the capture passed liveness check
        engine: name of the matching engine
        details: additional diagnostic information
    """
    start_time = time.time()

    # --- Validate inputs ---
    if document is None or document.filename == "":
        raise HTTPException(status_code=400, detail="A document image is required.")
    if live is None or live.filename == "":
        raise HTTPException(status_code=400, detail="A live capture image is required.")

    doc_bytes = await document.read()
    live_bytes = await live.read()

    if len(doc_bytes) == 0:
        raise HTTPException(status_code=400, detail="Document image is empty.")
    if len(live_bytes) == 0:
        raise HTTPException(status_code=400, detail="Live capture image is empty.")

    max_bytes = settings.MAX_IMAGE_SIZE_MB * 1024 * 1024
    if len(doc_bytes) > max_bytes:
        raise HTTPException(status_code=400, detail=f"Document image exceeds {settings.MAX_IMAGE_SIZE_MB}MB limit.")
    if len(live_bytes) > max_bytes:
        raise HTTPException(status_code=400, detail=f"Live capture exceeds {settings.MAX_IMAGE_SIZE_MB}MB limit.")

    try:
        doc_image = _decode_image(doc_bytes)
        live_image = _decode_image(live_bytes)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=f"Could not decode image: {e}")

    # --- Detect faces ---
    doc_faces = face_detector.detect(doc_image)
    live_faces = face_detector.detect(live_image)

    doc_face_found = len(doc_faces) > 0
    live_face_found = len(live_faces) > 0
    multiple_doc_faces = len(doc_faces) > 1
    multiple_live_faces = len(live_faces) > 1

    details = {
        "docFacesDetected": len(doc_faces),
        "liveFacesDetected": len(live_faces),
        "engine": "retinaface-arcface",
    }

    # --- Handle detection failures ---
    if not doc_face_found:
        return _build_response(
            similarity=0.0,
            doc_face_found=False,
            live_face_found=live_face_found,
            liveness_score=0.0,
            liveness_live=False,
            details=details,
            start_time=start_time,
            error="NO_FACE_ON_DOCUMENT",
        )

    if not live_face_found:
        return _build_response(
            similarity=0.0,
            doc_face_found=True,
            live_face_found=False,
            liveness_score=0.0,
            liveness_live=False,
            details=details,
            start_time=start_time,
            error="NO_FACE_IN_CAPTURE",
        )

    if multiple_doc_faces:
        details["warning"] = "Multiple faces detected on document; using highest confidence face"
    if multiple_live_faces:
        details["warning"] = "Multiple faces detected in live capture; using highest confidence face"

    # --- Liveness check on live face ---
    liveness_result = liveness_detector.check_liveness(live_image, live_faces[0])
    details["liveness"] = liveness_result.to_dict()

    # --- Extract embeddings ---
    doc_embedding = face_recognizer.extract_embedding(doc_image, doc_faces[0])
    live_embedding = face_recognizer.extract_embedding(live_image, live_faces[0])

    if doc_embedding is None:
        return _build_response(
            similarity=0.0,
            doc_face_found=True,
            live_face_found=True,
            liveness_score=liveness_result.score,
            liveness_live=liveness_result.is_live,
            details=details,
            start_time=start_time,
            error="EMBEDDING_EXTRACTION_FAILED_DOCUMENT",
        )

    if live_embedding is None:
        return _build_response(
            similarity=0.0,
            doc_face_found=True,
            live_face_found=True,
            liveness_score=liveness_result.score,
            liveness_live=liveness_result.is_live,
            details=details,
            start_time=start_time,
            error="EMBEDDING_EXTRACTION_FAILED_LIVE",
        )

    details["embeddingDimension"] = len(doc_embedding.embedding)

    # --- Compare embeddings ---
    similarity = face_recognizer.cosine_similarity(doc_embedding.embedding, live_embedding.embedding)

    return _build_response(
        similarity=similarity,
        doc_face_found=True,
        live_face_found=True,
        liveness_score=liveness_result.score,
        liveness_live=liveness_result.is_live,
        details=details,
        start_time=start_time,
    )


def _build_response(
    similarity: float,
    doc_face_found: bool,
    live_face_found: bool,
    liveness_score: float,
    liveness_live: bool,
    details: dict,
    start_time: float,
    error: str | None = None,
) -> dict:
    """Build the standard response matching the Java HttpFaceVerifier contract."""
    elapsed_ms = (time.time() - start_time) * 1000
    details["elapsedMs"] = round(elapsed_ms, 1)

    if error:
        details["error"] = error

    return {
        "similarity": round(max(0.0, min(1.0, similarity)), 4),
        "documentFaceFound": doc_face_found,
        "liveFaceFound": live_face_found,
        "livenessScore": round(max(0.0, min(1.0, liveness_score)), 4),
        "livenessLive": liveness_live,
        "engine": details.get("engine", "retinaface-arcface"),
        "details": details,
    }


if __name__ == "__main__":
    uvicorn.run(
        "app:app",
        host=settings.HOST,
        port=settings.PORT,
        reload=settings.DEBUG,
    )
