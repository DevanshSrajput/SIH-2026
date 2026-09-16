"""Face Verification Service - FastAPI Application.

Compares a document portrait against a live camera capture, assesses whether
either image is good enough to decide on, checks the capture for spoofing, and
returns one of three answers: MATCH, NO_MATCH or UNCERTAIN.

The third answer is the point. A biometric comparison that is not confident must
say so rather than rounding itself to the nearest verdict, because the officer
reading the screen has no other way to tell a strong result from a weak one.

Endpoints:
    GET  /health      - liveness probe and model availability
    GET  /config      - the thresholds this node is running with
    POST /compare     - 1:1 verification, document portrait against live capture
    POST /quality     - quality assessment for a single image
    POST /embed       - extract an embedding, for a caller that stores its own gallery
    POST /enrol       - register a face against a subject in the local gallery
    POST /identify    - 1:N identification against the local gallery
    GET  /enrolments  - list the local gallery
    DELETE /enrolments/{id} - remove one enrolment
"""

import logging
import time
from contextlib import asynccontextmanager
from typing import Optional

import cv2
import numpy as np
import uvicorn
from fastapi import FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import JSONResponse

from config import settings
from decision import Decision, decide
from enrolment_store import enrolment_store, resolve_identification
from face_detector import face_detector
from face_quality import assess_quality
from face_recognizer import face_recognizer
from liveness_detector import liveness_detector

logging.basicConfig(
    level=getattr(logging, settings.LOG_LEVEL.upper(), logging.INFO),
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
)
logger = logging.getLogger(__name__)

ENGINE_NAME = "opencv-yunet-sface"


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Load models on startup."""
    logger.info("Starting Face Verification Service")
    logger.info("Model directory: %s", settings.MODEL_DIR)
    logger.info(
        "Decision thresholds (raw cosine): match >= %.2f, mismatch < %.2f, margin %.2f",
        settings.MATCH_THRESHOLD,
        settings.MISMATCH_THRESHOLD,
        settings.UNCERTAIN_MARGIN,
    )
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
        "Compares a document portrait against a live camera capture. Returns a similarity "
        "score, an image-quality verdict, a liveness check, and a three-way decision "
        "(MATCH / NO_MATCH / UNCERTAIN)."
    ),
    version="2.0.0",
    lifespan=lifespan,
)


# ----------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------


def _decode_image(image_bytes: bytes) -> np.ndarray:
    """Decode image bytes to a BGR numpy array."""
    nparr = np.frombuffer(image_bytes, np.uint8)
    image = cv2.imdecode(nparr, cv2.IMREAD_COLOR)
    if image is None:
        raise ValueError("Could not decode image")
    return image


async def _read_upload(upload: Optional[UploadFile], field: str) -> bytes:
    """Read and validate one uploaded image."""
    if upload is None or not upload.filename:
        raise HTTPException(status_code=400, detail=f"A {field} image is required.")

    data = await upload.read()
    if len(data) == 0:
        raise HTTPException(status_code=400, detail=f"The {field} image is empty.")

    max_bytes = settings.MAX_IMAGE_SIZE_MB * 1024 * 1024
    if len(data) > max_bytes:
        raise HTTPException(
            status_code=413,
            detail=f"The {field} image exceeds the {settings.MAX_IMAGE_SIZE_MB}MB limit.",
        )
    return data


def _thresholds() -> dict:
    return {
        "match": settings.MATCH_THRESHOLD,
        "mismatch": settings.MISMATCH_THRESHOLD,
        "uncertainMargin": settings.UNCERTAIN_MARGIN,
        "qualityMinScore": settings.QUALITY_MIN_SCORE,
        "identify": settings.IDENTIFY_THRESHOLD,
        "identifyMargin": settings.IDENTIFY_MARGIN,
        "scale": "raw-cosine",
    }


def _response(
    *,
    decision: str,
    similarity: float,
    confidence: float,
    doc_face_found: bool,
    live_face_found: bool,
    liveness_score: float,
    liveness_live: bool,
    details: dict,
    start_time: float,
    summary: str,
    reasons: Optional[list[str]] = None,
    blockers: Optional[list[str]] = None,
    recapture_advice: Optional[str] = None,
    error: Optional[str] = None,
) -> dict:
    """Build the response contract the Java backend reads.

    ``similarity`` is the raw cosine clamped at zero. A negative cosine and a zero
    cosine both mean "nothing in common", so the distinction is not worth carrying
    into the API, but the unclamped value stays in ``details`` for the audit trail.
    """
    details["elapsedMs"] = round((time.time() - start_time) * 1000, 1)
    if error:
        details["error"] = error

    return {
        "similarity": round(max(0.0, min(1.0, similarity)), 4),
        "decision": decision,
        "confidence": round(max(0.0, min(1.0, confidence)), 4),
        "summary": summary,
        "reasons": reasons or [],
        "blockers": blockers or [],
        "recaptureAdvice": recapture_advice,
        "documentFaceFound": doc_face_found,
        "liveFaceFound": live_face_found,
        "livenessScore": round(max(0.0, min(1.0, liveness_score)), 4),
        "livenessLive": liveness_live,
        "engine": ENGINE_NAME,
        "thresholds": _thresholds(),
        "details": details,
    }


# ----------------------------------------------------------------------
# Health and configuration
# ----------------------------------------------------------------------


@app.get("/health")
async def health():
    """Health check endpoint."""
    detector_ok = face_detector.is_available
    recognizer_ok = face_recognizer.is_available
    return {
        "status": "ok" if (detector_ok and recognizer_ok) else "degraded",
        "detector_available": detector_ok,
        "recognizer_available": recognizer_ok,
        "engine": ENGINE_NAME,
        "enrolments": enrolment_store.count,
    }


@app.get("/config")
async def config():
    """The thresholds this node is running with.

    Published so the calling backend can align its own bands with the service's
    rather than keeping a second, silently diverging copy of them.
    """
    return {
        "engine": ENGINE_NAME,
        "thresholds": _thresholds(),
        "livenessEnabled": settings.LIVENESS_ENABLED,
        "livenessThreshold": settings.LIVENESS_THRESHOLD,
        "minDetectionConfidence": settings.DETECTION_CONFIDENCE,
    }


# ----------------------------------------------------------------------
# 1:1 verification
# ----------------------------------------------------------------------


@app.post("/compare")
async def compare_faces(
    document: UploadFile = File(..., description="Document image containing the portrait photo"),
    live: UploadFile = File(..., description="Live camera capture of the person"),
):
    """Compare a document portrait against a live capture.

    Returns a three-way decision rather than a bare score. A positive
    identification requires a decisive similarity AND two usable images AND a
    passed liveness check AND exactly one face in the live frame. Anything short
    of that is reported as UNCERTAIN, with the reason and what to do about it.
    """
    start_time = time.time()

    doc_bytes = await _read_upload(document, "document")
    live_bytes = await _read_upload(live, "live capture")

    try:
        doc_image = _decode_image(doc_bytes)
        live_image = _decode_image(live_bytes)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=f"Could not decode image: {e}")

    # --- Detect ---
    doc_faces = face_detector.detect(doc_image)
    live_faces = face_detector.detect(live_image)

    doc_face_found = len(doc_faces) > 0
    live_face_found = len(live_faces) > 0

    details = {
        "docFacesDetected": len(doc_faces),
        "liveFacesDetected": len(live_faces),
        "engine": ENGINE_NAME,
    }

    if not doc_face_found or not live_face_found:
        missing = "document" if not doc_face_found else "live capture"
        return _response(
            decision=Decision.UNCERTAIN.value,
            similarity=0.0,
            confidence=0.0,
            doc_face_found=doc_face_found,
            live_face_found=live_face_found,
            liveness_score=0.0,
            liveness_live=False,
            details=details,
            start_time=start_time,
            summary=f"No face could be located in the {missing}, so no comparison was possible.",
            reasons=[f"No face was detected in the {missing}."],
            blockers=[f"No face was detected in the {missing}."],
            recapture_advice=(
                "Re-scan the document so the portrait is fully visible."
                if not doc_face_found
                else "Re-capture the traveller facing the camera."
            ),
            error="NO_FACE_ON_DOCUMENT" if not doc_face_found else "NO_FACE_IN_CAPTURE",
        )

    doc_face = doc_faces[0]
    live_face = live_faces[0]

    # --- Quality ---
    doc_quality = assess_quality(doc_image, doc_face, source="document")
    live_quality = assess_quality(live_image, live_face, source="live")
    details["quality"] = {
        "document": doc_quality.to_dict(),
        "live": live_quality.to_dict(),
    }

    # --- Liveness ---
    if settings.LIVENESS_ENABLED:
        liveness_result = liveness_detector.check_liveness(live_image, live_face)
        details["liveness"] = liveness_result.to_dict()
        liveness_score = liveness_result.score
        liveness_live = liveness_result.is_live
        liveness_reason = liveness_result.overall_reason
    else:
        liveness_score, liveness_live, liveness_reason = 1.0, True, ""
        details["liveness"] = {"enabled": False}

    # --- Embed ---
    doc_embedding = face_recognizer.extract_embedding(doc_image, doc_face)
    live_embedding = face_recognizer.extract_embedding(live_image, live_face)

    if doc_embedding is None or live_embedding is None:
        which = "document portrait" if doc_embedding is None else "live capture"
        return _response(
            decision=Decision.UNCERTAIN.value,
            similarity=0.0,
            confidence=0.0,
            doc_face_found=True,
            live_face_found=True,
            liveness_score=liveness_score,
            liveness_live=liveness_live,
            details=details,
            start_time=start_time,
            summary=f"A face embedding could not be computed for the {which}.",
            reasons=[f"Embedding extraction failed for the {which}."],
            blockers=[f"Embedding extraction failed for the {which}."],
            recapture_advice="Re-capture both images and try again.",
            error=(
                "EMBEDDING_EXTRACTION_FAILED_DOCUMENT"
                if doc_embedding is None
                else "EMBEDDING_EXTRACTION_FAILED_LIVE"
            ),
        )

    # --- Compare ---
    cosine = face_recognizer.cosine_similarity(
        doc_embedding.embedding, live_embedding.embedding
    )
    l2 = face_recognizer.l2_distance(doc_embedding.embedding, live_embedding.embedding)

    details["cosineSimilarity"] = round(cosine, 4)
    details["l2Distance"] = round(l2, 4)
    details["embeddingDimension"] = int(len(doc_embedding.embedding))
    details["alignmentUsed"] = bool(
        doc_embedding.alignment_used and live_embedding.alignment_used
    )
    details["docFaceConfidence"] = round(float(doc_face.confidence), 4)
    details["liveFaceConfidence"] = round(float(live_face.confidence), 4)

    verdict = decide(
        similarity=cosine,
        document_quality=doc_quality,
        live_quality=live_quality,
        liveness_live=liveness_live,
        liveness_score=liveness_score,
        liveness_reason=liveness_reason,
        document_face_count=len(doc_faces),
        live_face_count=len(live_faces),
        alignment_used=details["alignmentUsed"],
        liveness_enabled=settings.LIVENESS_ENABLED,
    )

    logger.info(
        "compare: cosine=%.4f decision=%s confidence=%.2f docQ=%.2f liveQ=%.2f live=%s",
        cosine,
        verdict.decision.value,
        verdict.confidence,
        doc_quality.score,
        live_quality.score,
        liveness_live,
    )

    return _response(
        decision=verdict.decision.value,
        # Negative cosine carries no more information than zero for the caller.
        similarity=max(0.0, cosine),
        confidence=verdict.confidence,
        doc_face_found=True,
        live_face_found=True,
        liveness_score=liveness_score,
        liveness_live=liveness_live,
        details=details,
        start_time=start_time,
        summary=verdict.summary,
        reasons=verdict.reasons,
        blockers=verdict.blockers,
        recapture_advice=verdict.recapture_advice,
    )


# ----------------------------------------------------------------------
# Quality and embedding
# ----------------------------------------------------------------------


@app.post("/quality")
async def check_quality(
    image: UploadFile = File(..., description="Image to assess"),
    source: str = Form("live", description="'live' or 'document'"),
):
    """Assess whether an image is good enough to identify someone on.

    Used by the console to warn an officer before a capture is submitted, so a
    bad frame is caught at the desk rather than surfacing as an inconclusive
    result thirty seconds later.
    """
    start_time = time.time()
    data = await _read_upload(image, "image")

    try:
        frame = _decode_image(data)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=f"Could not decode image: {e}")

    source = "document" if source.lower().startswith("doc") else "live"
    faces = face_detector.detect(frame)

    if not faces:
        return {
            "faceFound": False,
            "facesDetected": 0,
            "quality": assess_quality(frame, None, source=source).to_dict(),
            "elapsedMs": round((time.time() - start_time) * 1000, 1),
        }

    quality = assess_quality(frame, faces[0], source=source)
    result = {
        "faceFound": True,
        "facesDetected": len(faces),
        "quality": quality.to_dict(),
        "faceBox": [round(float(v), 1) for v in faces[0].bbox],
        "detectionConfidence": round(float(faces[0].confidence), 4),
        "elapsedMs": round((time.time() - start_time) * 1000, 1),
    }
    if len(faces) > 1:
        result["warning"] = (
            f"{len(faces)} faces are in frame. Only one person should be captured."
        )
    return result


@app.post("/embed")
async def embed(
    image: UploadFile = File(..., description="Image containing exactly one face"),
    source: str = Form("live", description="'live' or 'document'"),
):
    """Extract a face embedding, for a caller that keeps its own gallery.

    This is how the Java backend builds the MongoDB enrolment registry: the model
    stays here, the records stay there.
    """
    start_time = time.time()
    data = await _read_upload(image, "image")

    try:
        frame = _decode_image(data)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=f"Could not decode image: {e}")

    source = "document" if source.lower().startswith("doc") else "live"
    faces = face_detector.detect(frame)
    if not faces:
        raise HTTPException(status_code=422, detail="No face was detected in the image.")

    face = faces[0]
    quality = assess_quality(frame, face, source=source)
    embedding = face_recognizer.extract_embedding(frame, face)
    if embedding is None:
        raise HTTPException(status_code=422, detail="Could not compute a face embedding.")

    normalised = face_recognizer.normalise(embedding.embedding)

    return {
        "embedding": [round(float(v), 6) for v in normalised],
        "dimension": int(len(normalised)),
        "normalised": True,
        "quality": quality.to_dict(),
        "facesDetected": len(faces),
        "alignmentUsed": embedding.alignment_used,
        "detectionConfidence": round(float(face.confidence), 4),
        "engine": ENGINE_NAME,
        "elapsedMs": round((time.time() - start_time) * 1000, 1),
    }


# ----------------------------------------------------------------------
# Enrolment and 1:N identification
# ----------------------------------------------------------------------


@app.post("/enrol")
async def enrol(
    image: UploadFile = File(..., description="Reference image of the traveller"),
    subjectId: str = Form(..., description="Stable identifier for this person"),
    displayName: Optional[str] = Form(None),
    documentNumber: Optional[str] = Form(None),
    nationality: Optional[str] = Form(None),
    enrolledBy: Optional[str] = Form(None),
    notes: Optional[str] = Form(None),
    source: str = Form("live"),
    force: bool = Form(False, description="Enrol even if the image fails the quality gate"),
):
    """Register a reference face for a known traveller.

    A poor-quality enrolment is worse than none: it becomes a permanent source of
    wrong answers for every future comparison against that subject. So the quality
    gate applies here too, and overriding it requires saying so explicitly.
    """
    start_time = time.time()
    data = await _read_upload(image, "image")

    if not subjectId or not subjectId.strip():
        raise HTTPException(status_code=400, detail="A subjectId is required.")

    try:
        frame = _decode_image(data)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=f"Could not decode image: {e}")

    normalised_source = "document" if source.lower().startswith("doc") else "live"
    faces = face_detector.detect(frame)
    if not faces:
        raise HTTPException(status_code=422, detail="No face was detected in the image.")
    if len(faces) > 1:
        raise HTTPException(
            status_code=422,
            detail=f"{len(faces)} faces are in frame. Enrol from an image of one person.",
        )

    face = faces[0]
    quality = assess_quality(frame, face, source=normalised_source)

    if not quality.usable and not force:
        return JSONResponse(
            status_code=422,
            content={
                "enrolled": False,
                "reason": "The reference image is not good enough to enrol.",
                "quality": quality.to_dict(),
                "advice": quality.advice,
                "hint": "Re-capture, or resubmit with force=true to accept it anyway.",
            },
        )

    embedding = face_recognizer.extract_embedding(frame, face)
    if embedding is None:
        raise HTTPException(status_code=422, detail="Could not compute a face embedding.")

    record = enrolment_store.enrol(
        subject_id=subjectId.strip(),
        embedding=embedding.embedding,
        quality_score=quality.score,
        display_name=displayName,
        document_number=documentNumber,
        nationality=nationality,
        enrolled_by=enrolledBy,
        notes=notes,
        metadata={
            "source": normalised_source,
            "forced": bool(force),
            "alignmentUsed": embedding.alignment_used,
        },
    )

    return {
        "enrolled": True,
        "enrolment": record.to_summary(),
        "quality": quality.to_dict(),
        "qualityOverridden": bool(force and not quality.usable),
        "totalEnrolments": enrolment_store.count,
        "elapsedMs": round((time.time() - start_time) * 1000, 1),
    }


@app.post("/identify")
async def identify(
    image: UploadFile = File(..., description="Live capture of the person to identify"),
    limit: int = Form(5, description="How many candidates to return"),
    checkLiveness: bool = Form(True),
):
    """Identify a face against the enrolled gallery (1:N).

    Returns a ranked candidate list and an explicit verdict. A top score that
    beats the threshold but sits too close to the runner-up is reported as
    AMBIGUOUS rather than named - with a large gallery, the nearest neighbour is
    not automatically the right one.
    """
    start_time = time.time()
    data = await _read_upload(image, "image")

    try:
        frame = _decode_image(data)
    except ValueError as e:
        raise HTTPException(status_code=400, detail=f"Could not decode image: {e}")

    faces = face_detector.detect(frame)
    if not faces:
        return {
            "identified": False,
            "decision": "NO_FACE",
            "reason": "No face was detected in the image.",
            "candidates": [],
            "elapsedMs": round((time.time() - start_time) * 1000, 1),
        }

    face = faces[0]
    quality = assess_quality(frame, face, source="live")

    blockers = []
    if not quality.usable:
        blockers.append(quality.issues[0] if quality.issues else "Image quality is insufficient.")
    if len(faces) > 1:
        blockers.append(f"{len(faces)} faces are in frame.")

    liveness_payload = None
    if checkLiveness and settings.LIVENESS_ENABLED:
        liveness_result = liveness_detector.check_liveness(frame, face)
        liveness_payload = liveness_result.to_dict()
        if not liveness_result.is_live:
            blockers.append("The capture failed the liveness check.")

    if blockers:
        # Do not name anybody from evidence that would not survive a challenge.
        return {
            "identified": False,
            "decision": "UNCERTAIN",
            "reason": "; ".join(blockers),
            "advice": quality.advice or "Re-capture the traveller and try again.",
            "quality": quality.to_dict(),
            "liveness": liveness_payload,
            "candidates": [],
            "elapsedMs": round((time.time() - start_time) * 1000, 1),
        }

    embedding = face_recognizer.extract_embedding(frame, face)
    if embedding is None:
        raise HTTPException(status_code=422, detail="Could not compute a face embedding.")

    candidates = enrolment_store.identify(
        embedding.embedding, limit=max(1, min(int(limit), 25))
    )
    result = resolve_identification(candidates)
    result["quality"] = quality.to_dict()
    result["liveness"] = liveness_payload
    result["gallerySize"] = enrolment_store.count
    result["elapsedMs"] = round((time.time() - start_time) * 1000, 1)
    return result


@app.get("/enrolments")
async def list_enrolments():
    """Every enrolment in the local gallery, without the embeddings."""
    return {
        "count": enrolment_store.count,
        "enrolments": enrolment_store.list_subjects(),
    }


@app.delete("/enrolments/{enrolment_id}")
async def delete_enrolment(enrolment_id: str):
    """Remove one enrolment from the local gallery."""
    if not enrolment_store.delete(enrolment_id):
        raise HTTPException(status_code=404, detail=f"Unknown enrolment {enrolment_id}")
    return {"deleted": True, "enrolmentId": enrolment_id, "remaining": enrolment_store.count}


if __name__ == "__main__":
    uvicorn.run(
        "app:app",
        host=settings.HOST,
        port=settings.PORT,
        reload=settings.DEBUG,
    )
