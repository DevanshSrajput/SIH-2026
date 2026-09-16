# Face Verification Service

Module 4 of the screening platform. Compares the portrait printed on an identity
document against a live capture of the person presenting it, and answers one of three
ways: **MATCH**, **NO_MATCH**, or **UNCERTAIN**.

The third answer is the design. A biometric comparison that is not confident has to say
so, because the officer reading the screen has no other way to tell a strong result from
a weak one. A positive identification is issued only when the score is decisive *and*
nothing about the comparison undermines it.

## The decision rule

A comparison returns `MATCH` only when **all** of these hold:

| Condition | Why |
|---|---|
| Similarity ≥ `MATCH_THRESHOLD`, by more than `UNCERTAIN_MARGIN` | A score sitting on the threshold is not a clear result |
| Both images pass the quality gate | A blurred or dark face produces an unreliable embedding, in either direction |
| The live capture passes the liveness check | Otherwise it may be a printed photo or a phone screen |
| Exactly one face in the live frame | With two, it is not certain who was compared |
| Both crops were landmark-aligned | An unaligned crop shifts embeddings toward each other |

Anything else returns `UNCERTAIN`, carrying `blockers` (what went wrong) and
`recaptureAdvice` (what to do about it).

The asymmetry is deliberate. A false accept at a checkpoint lets an impostor through on
someone else's passport; a false referral costs an officer a few seconds. Those are not
the same mistake, and the thresholds are not set as though they were.

`NO_MATCH` is also withheld when both images are unusable — telling an officer the
traveller is an impostor on the strength of a dark, blurred photograph is its own kind
of false positive.

## Thresholds are raw cosine, not percentages

`similarity` is the **raw cosine** between two 128-dimensional SFace embeddings, in
[0, 1] after clamping. It is *not* a rescaled confidence and must not be read as "82%
sure".

| Population | Typical raw cosine |
|---|---|
| Two different people | 0.00 – 0.25 |
| Same person, document scan vs live capture | 0.40 – 0.75 |

SFace's own published break-even point is **0.363**. The shipped `MATCH_THRESHOLD` of
**0.46** sits deliberately above it, buying a lower false-accept rate at the cost of
more referrals.

> **Calibrate before going live.** A threshold copied from a paper is a guess about your
> cameras, your lighting and your document scanners.
>
> ```bash
> python calibrate.py samples/        # one subdirectory per person
> ```
>
> It reports the genuine and impostor distributions measured on *your* images, the false
> accept rate at the configured threshold, and the threshold that would give zero false
> accepts. If the FAR is not zero, raise the threshold until it is and accept the extra
> referrals.

## Image quality gate

Measured before any identity claim is made, because an embedding is only as trustworthy
as the pixels behind it.

| Check | Rejects |
|---|---|
| `resolution` | Faces too few pixels across to identify (90px live, 60px document) |
| `sharpness` | Blurred captures (variance of Laplacian, normalised to a 128px reference) |
| `exposure` | Under- or over-exposed faces, and heavy black/white clipping |
| `contrast` | Flat, washed-out faces carrying no detail |
| `pose` | Heads turned away or tilted, from the five landmarks |
| `detection` | Faces the detector was not confident were faces |

Every check must pass, not merely the weighted average — a face at mean luminance 32 is
too dark to identify anyone on, however good its resolution and focus are.

Document portraits are held to a lower bar on resolution and detector confidence: they
are printed small, overprinted with security patterns, and re-scanned.

## API

| Endpoint | Purpose |
|---|---|
| `GET /health` | Liveness probe and model availability |
| `GET /config` | The thresholds this node is running with |
| `POST /compare` | 1:1 verification — `document` + `live` |
| `POST /quality` | Quality assessment for a single frame — `image`, `source` |
| `POST /embed` | Extract an embedding, for a caller keeping its own gallery |
| `POST /enrol` | Register a face in the local gallery |
| `POST /identify` | 1:N identification against the local gallery |
| `GET /enrolments` | List the local gallery |
| `DELETE /enrolments/{id}` | Remove one enrolment |

Interactive docs at `/docs` when the service is running.

### `POST /compare`

```bash
curl -X POST http://localhost:5000/compare \
  -F "document=@passport.jpg" \
  -F "live=@capture.jpg"
```

```json
{
  "similarity": 0.58,
  "decision": "MATCH",
  "confidence": 0.71,
  "summary": "The traveller matches the portrait on the document (similarity 0.58).",
  "reasons": ["Similarity 0.580 is at or above the 0.46 match threshold."],
  "blockers": [],
  "recaptureAdvice": null,
  "documentFaceFound": true,
  "liveFaceFound": true,
  "livenessScore": 0.87,
  "livenessLive": true,
  "engine": "opencv-yunet-sface",
  "thresholds": { "match": 0.46, "mismatch": 0.28, "scale": "raw-cosine" },
  "details": { "quality": { "document": {...}, "live": {...} }, "...": "..." }
}
```

`confidence` is reported separately from `similarity` on purpose: a high similarity with
a low confidence is exactly the case an officer most needs to see.

### 1:N identification

Identification is held to a stricter bar than verification. Every additional enrolled
subject is another chance for a coincidental high score, so the top candidate must clear
`IDENTIFY_THRESHOLD` **and** beat the highest-scoring *different* person by
`IDENTIFY_MARGIN`. Two near-equal candidates return `AMBIGUOUS` rather than naming
either — that is a pair of lookalikes, not an identification.

## Liveness detection

Six heuristics, weighted: texture variance, frequency analysis (halftone and moiré),
colour-space distribution, edge sharpness, face-size ratio, and sensor-noise uniformity.

These catch a printed photograph or a face held up on a phone screen. They are not a
defence against a good 3D mask or a video replay on a high-quality display; that needs
depth or infrared hardware, which this service does not assume exists.

## Configuration

Every setting is an environment variable. Defaults are in `config.py`.

| Variable | Default | Purpose |
|---|---|---|
| `FACE_SERVICE_PORT` | `5000` | Listen port |
| `MATCH_THRESHOLD` | `0.46` | Raw cosine at or above which faces match |
| `MISMATCH_THRESHOLD` | `0.28` | Raw cosine below which they do not |
| `UNCERTAIN_MARGIN` | `0.04` | Band around each threshold reported as uncertain |
| `IDENTIFY_THRESHOLD` | `0.52` | 1:N identification threshold |
| `IDENTIFY_MARGIN` | `0.06` | Margin the top candidate must beat the runner-up by |
| `QUALITY_MIN_SCORE` | `0.55` | Aggregate quality floor |
| `MIN_DECISION_CONFIDENCE` | `0.80` | Detector confidence needed to decide |
| `LIVENESS_ENABLED` | `true` | Whether liveness gates a positive identification |
| `LIVENESS_THRESHOLD` | `0.5` | Liveness score needed to pass |
| `FACE_ENROLMENT_DB` | `data/enrolments.json` | Local gallery file |
| `MAX_IMAGE_SIZE_MB` | `20` | Upload limit |

## Setup

```bash
pip install -r requirements.txt
python download_models.py      # fetches YuNet + SFace into models/
python app.py                  # or: uvicorn app:app --host 0.0.0.0 --port 5000
```

## Tests

```bash
pytest tests/ -q
```

Covers the decision engine, the quality gate, the detector's output layout, and the
liveness heuristics. The suite's central property is one-sided: the service may be wrong
by being unsure, but never by being confident — so every path that could produce a MATCH
on evidence that does not support one is asserted.

## Models

| Model | File | Role |
|---|---|---|
| YuNet | `models/yunet.onnx` | Face detection and five facial landmarks |
| SFace | `models/face_recognizer_fast.onnx` | 128-dimensional face embeddings |

Both come from the [OpenCV Zoo](https://github.com/opencv/opencv_zoo) and are fetched by
`download_models.py`. The Docker build runs it at build time, so the image ships with the
models inside it and never reaches the network at runtime — a border post is exactly the
deployment that cannot assume outbound internet.

> **YuNet's row layout**: `[x, y, w, h, 10 landmark coords, score]`. The score is the
> **last** element. Reading index 4 as the confidence instead takes the right eye's
> x-coordinate and shifts every landmark by one slot, which feeds `alignCrop` a face
> warped to the wrong canonical position. That degrades every embedding and pulls
> unrelated faces closer together — a false-accept source that hides in an array index
> and surfaces only as slightly worse scores. `tests/test_decision.py` pins it.

## Connecting to the Java backend

```bash
export FACE_SERVICE_URL=http://localhost:5000
```

`HttpFaceVerifier` posts to `/compare` and passes the service's `decision` straight
through — the matcher saw the pixels and the screening service did not. Leave the URL
unset and Module 4 reports `SKIPPED` rather than guessing a score.

`FaceEmbeddingClient` calls `/embed` to build the authoritative enrolment registry in
MongoDB. The model lives here; the records live there.
