# Face Verification Service

Standalone Python microservice for face comparison with liveness/anti-spoofing detection.
Designed to be called by the Java backend's `HttpFaceVerifier` via `POST /compare`.

## Architecture

```
Document Image ──→ RetinaFace Detection ──→ ArcFace Embedding ──┐
                                                                 ├── Cosine Similarity ──→ Result
Live Capture ──→ RetinaFace Detection ──→ ArcFace Embedding ──┘
                           │
                    Liveness Checks
                    (texture, frequency, color, edge, noise)
```

## Technologies

| Component | Technology |
|---|---|
| Face Detection | RetinaFace (insightface) |
| Face Recognition | ArcFace R100 (insightface, 512-dim embeddings) |
| Similarity Metric | Cosine Similarity mapped to [0, 1] |
| Anti-Spoofing | Multi-signal heuristic liveness detection |
| API Framework | FastAPI + Uvicorn |
| Image Processing | OpenCV |

## Liveness Detection Checks

| Check | Weight | What it detects |
|---|---|---|
| Texture Variance | 25% | Printed photos have lower texture variance |
| Frequency Analysis | 25% | Screen captures show moiré patterns in FFT |
| Color Space | 15% | Printed/screen images have unnatural HSV distribution |
| Edge Sharpness | 15% | Screens show aliasing; prints show halftone edges |
| Face Size Ratio | 10% | Phone-held photos have unusual face-to-frame ratio |
| Noise Pattern | 10% | Real cameras have consistent sensor noise |

## Setup

### Local Development

```bash
cd face-verification-service
pip install -r requirements.txt
python download_models.py  # Downloads ~200MB of models
python app.py              # Starts on http://localhost:5000
```

### Docker

```bash
cd face-verification-service
docker build -t face-verification-service .
docker run -p 5000:5000 face-verification-service
```

### Connect to Java Backend

Set the environment variable:
```bash
export FACE_SERVICE_URL=http://localhost:5000
```

Or in `application.yml`:
```yaml
screening:
  face:
    service-url: http://localhost:5000
```

## API Endpoints

### `GET /health`
Health check. Returns service status and model availability.

### `POST /compare`
Compare a document portrait against a live capture.

**Request:** `multipart/form-data`
- `document`: Document image file (containing portrait photo)
- `live`: Live camera capture file

**Response:**
```json
{
  "similarity": 0.82,
  "documentFaceFound": true,
  "liveFaceFound": true,
  "livenessScore": 0.87,
  "livenessLive": true,
  "engine": "retinaface-arcface",
  "details": {
    "docFacesDetected": 1,
    "liveFacesDetected": 1,
    "embeddingDimension": 512,
    "liveness": {
      "score": 0.87,
      "is_live": true,
      "checks": [...]
    },
    "elapsedMs": 234.5
  }
}
```

## Configuration

All settings via environment variables:

| Variable | Default | Description |
|---|---|---|
| `FACE_SERVICE_HOST` | `0.0.0.0` | Bind host |
| `FACE_SERVICE_PORT` | `5000` | Bind port |
| `FACE_MODELS_DIR` | `~/.cache/face-verification-models` | Model cache directory |
| `DETECTION_CONFIDENCE` | `0.5` | Minimum face detection confidence |
| `MATCH_THRESHOLD` | `0.75` | Above = face match |
| `MISMATCH_THRESHOLD` | `0.55` | Below = face mismatch |
| `LIVENESS_THRESHOLD` | `0.5` | Above = likely live person |
| `MAX_IMAGE_SIZE_MB` | `20` | Maximum upload size |

## Running Tests

```bash
cd face-verification-service
python -m pytest tests/ -v
```

## Error Handling

| Error | HTTP Status | Meaning |
|---|---|---|
| `NO_FACE_ON_DOCUMENT` | 200 | No face detected in document image |
| `NO_FACE_IN_CAPTURE` | 200 | No face detected in live capture |
| `EMBEDDING_EXTRACTION_FAILED_*` | 200 | Could not generate embedding |
| Missing document/live | 400 | Required image not provided |
| Empty image | 400 | Uploaded file has no data |
| Image too large | 400 | Exceeds MAX_IMAGE_SIZE_MB |
| Undecodable image | 400 | Not a valid image file |

## Integration with Screening Pipeline

This service integrates via the existing `HttpFaceVerifier` in the Java backend:

1. Java `ScreeningService` calls `FaceVerificationService.verify()`
2. `FaceVerificationService` selects `HttpFaceVerifier` (priority over local)
3. `HttpFaceVerifier` sends `POST {service-url}/compare` with document + live images
4. This Python service processes the request and returns the result
5. Java backend maps the response to `FaceMatchResult` and continues the pipeline
