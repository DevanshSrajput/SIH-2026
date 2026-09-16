# REST API

Base URL `http://localhost:8080`. All endpoints return JSON. Null fields are omitted.

An OpenAPI 3 description of everything below is generated from the controllers and served
by the running application:

| | |
|---|---|
| Swagger UI | <http://localhost:8080/swagger-ui.html> |
| OpenAPI document | <http://localhost:8080/v3/api-docs> |

The generated document is the authority on request and response shapes — it cannot drift
from the deployed code. This page stays the place for the reasoning behind them.

> **No authentication.** The API is currently unauthenticated and must sit behind an
> authenticating gateway before deployment. See [TASKS.md](TASKS.md).

---

## Screening

### `POST /api/screenings`

Screens one presented document. `multipart/form-data`.

**Parts**

| Part | Required | Description |
|---|---|---|
| `document` | yes | The document image (JPEG/PNG). Max 20 MB |
| `live` | no | Live capture of the traveller. Enables Module 4 |

**Query parameters**

| Parameter | Default | Description |
|---|---|---|
| `documentType` | `UNKNOWN` | `PASSPORT`, `VISA`, `NATIONAL_ID`, `DRIVING_LICENCE`, `PERMIT`, `TRAVEL_AUTHORIZATION`, `UNKNOWN`. A hint only — modules report what they find |
| `checkpointId` | – | Recorded on the case |
| `laneId` | – | Recorded on the case |
| `officerId` | – | Recorded on the case and the audit trail |
| `text` | – | Text the caller already holds: an e-passport chip read or a keyed MRZ. Trusted over pixel OCR when present |

**Example**

```bash
curl -F "document=@passport.jpg" -F "text=<mrz.txt" "http://localhost:8080/api/screenings?documentType=PASSPORT&officerId=off-114"
```

**Response `200`** — the full `ScreeningCase`:

```json
{
  "caseReference": "BRD-FPW5DK",
  "status": "COMPLETED",
  "documentType": "PASSPORT",
  "processingMillis": 477,
  "extracted": {
    "surname": "ERIKSSON",
    "givenNames": "ANNA MARIA",
    "documentNumber": "L898902C3",
    "issuingState": "SWE",
    "nationality": "SWE",
    "dateOfBirth": "1984-08-12",
    "dateOfExpiry": "2030-04-15",
    "sex": "F",
    "engine": "supplied-text",
    "ocrConfidence": 0.99,
    "mrz": {
      "format": "TD3",
      "checkDigits": { "documentNumber": true, "dateOfBirth": false, "dateOfExpiry": true },
      "composite": false
    }
  },
  "moduleResults": [
    {
      "module": "DOCUMENT_VALIDATION",
      "status": "COMPLETED",
      "durationMillis": 2,
      "flags": [ ... ],
      "details": { ... }
    }
  ],
  "risk": {
    "score": 75,
    "band": "CRITICAL",
    "verdict": "REJECT",
    "flags": [
      {
        "code": "MRZ_CHECKDIGIT_MISMATCH",
        "module": "DOCUMENT_VALIDATION",
        "severity": "HIGH",
        "message": "The MRZ check digit for the date of birth does not match ...",
        "evidence": { "field": "dateOfBirth", "mrzFormat": "TD3" }
      }
    ],
    "topReasons": [ "..." ],
    "explanation": "Risk score 75 from 5 finding(s) ..."
  }
}
```

`status` is `COMPLETED` or `FAILED`. Module `status` is `COMPLETED`, `SKIPPED` or `FAILED`.

### `GET /api/screenings?page=0&size=25`

Paged case summaries, newest first. Deliberately small per row so the list stays fast.

### `GET /api/screenings/{reference}`

One case in full. Accepts a case reference (`BRD-XXXXXX`) or an internal id.

### `GET /api/screenings/{reference}/images/{kind}`

Returns a stored evidence image. `kind` is `document` or `live`. `404` if not stored.

### `GET /api/screenings/{reference}/audit`

The append-only audit trail for the case, oldest first.

### `POST /api/screenings/{reference}/decision`

Records the officer's own determination. The system recommendation is not overwritten.

```json
{ "decision": "REVIEW", "officerId": "off-114", "notes": "Referred for secondary inspection" }
```

`decision` is `CLEAR`, `REVIEW` or `REJECT`.

---

## Watchlist

### `GET /api/watchlist?page=0&size=50`

Paged entries, newest first.

### `POST /api/watchlist`

```json
{
  "documentNumber": "L898902C3",
  "surname": "ERIKSSON",
  "givenNames": "ANNA MARIA",
  "dateOfBirth": "1974-08-12",
  "nationality": "SWE",
  "listType": "STOLEN_DOCUMENT",
  "severity": "CRITICAL",
  "reason": "Reported stolen by the issuing authority",
  "source": "INTERPOL SLTD",
  "addedBy": "off-114"
}
```

Provide **a document number, or a surname together with a date of birth**. A name alone is
too common to match on safely and is rejected with `400`.

`listType` — `STOLEN_DOCUMENT`, `REVOKED_DOCUMENT`, `ENTRY_BAN`, `WANTED`,
`VISA_OVERSTAY`, `LOCAL_INTEREST`. `severity` defaults to `CRITICAL`.

Name parts are taken separately rather than as one display string, because the identity key
is built from surname plus date of birth; guessing which word is the surname would produce
entries that silently never match.

### `DELETE /api/watchlist/{id}?actor=off-114`

**Deactivates** the entry; it is never deleted. Removing a row outright would erase the
reason a past case was rejected.

---

## Statistics

### `GET /api/stats?windowHours=24`

Checkpoint statistics over a window: total screenings, referral count and rate, median and
slowest processing time, breakdown by verdict and document type, the ten most frequent
finding codes, and the five highest-risk cases.

Processing time is reported as a **median** so one pathological image cannot distort the
number a supervisor uses to judge whether lanes are keeping up.

---

## Errors

```json
{ "timestamp": "2026-08-28T00:17:21Z", "status": 400, "error": "Bad Request", "message": "A document image is required." }
```

| Status | Cause |
|---|---|
| `400` | Missing document image, unknown case reference, invalid watchlist entry |
| `413` | Upload exceeds the 20 MB limit |
| `500` | Unexpected error; the pipeline itself contains module failures rather than propagating them |

---

## Configuration

| Property | Default | Purpose |
|---|---|---|
| `MONGODB_URI` | `mongodb://localhost:27017/govtid_screening` | Database |
| `SERVER_PORT` | `8080` | API port |
| `CORS_ORIGINS` | `http://localhost:5173` | Allowed console origins |
| `screening.risk.reject-threshold` | `70` | Score at or above which the verdict is REJECT |
| `screening.risk.review-threshold` | `35` | Score at or above which the case is referred |
| `screening.ocr.claude.enabled` | `true` | Vision OCR master switch; still inactive without a credential |
| `screening.ocr.claude.api-key` | *(empty)* | Vision OCR credential. Falls back to `ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN` in the environment |
| `screening.ocr.claude.effort` | `medium` | Reasoning effort for vision OCR |
| `TESSERACT_BINARY` | `tesseract` | Classical OCR binary |
| `FACE_SERVICE_URL` | *(empty)* | Biometric matcher; empty disables Module 4 |
| `screening.face.match-threshold` | `0.46` | Raw cosine at or above which faces match |
| `screening.face.mismatch-threshold` | `0.28` | Raw cosine below which they are different people |
| `screening.face.uncertain-margin` | `0.03` | Band around each threshold reported inconclusive |
| `screening.face.identify-threshold` | `0.52` | 1:N identification threshold |
| `screening.face.identify-margin` | `0.06` | Margin the top candidate must beat the runner-up by |
| `screening.rate-limit.requests-per-minute` | `60` | Per-client cap on write requests |
| `screening.watchlist.velocity-threshold` | `3` | Presentations in 24 h before velocity is flagged |

### Face thresholds are raw cosine, not percentages

`similarity` is the **raw cosine** between two SFace embeddings. It is not a rescaled
confidence and must not be read as one.

| Population | Typical raw cosine |
|---|---|
| Two different people | 0.00 – 0.25 |
| Same person, document scan vs live capture | 0.40 – 0.75 |

SFace's published break-even is 0.363; `match-threshold` sits deliberately above it,
because a false accept and a false referral do not cost the same thing at a checkpoint.

Calibrate against real captures before relying on these values:

```bash
python face-verification-service/calibrate.py samples/
```

### Face service contract

Module 4 expects `POST {service-url}/compare` accepting multipart parts `document` and
`live`. A minimal reply is:

```json
{ "similarity": 0.58, "documentFaceFound": true, "liveFaceFound": true }
```

A service that has assessed image quality and liveness itself returns the richer shape,
and its `decision` is passed straight through — the matcher saw the pixels and the
screening service did not:

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
  "quality": { "document": { "usable": true }, "live": { "usable": true } }
}
```

A missing `similarity` scores as 0.0 — the absence of a measurement is never read as a
perfect match. An unrecognised `decision` is treated as absent rather than guessed at, so
a service speaking a dialect we do not understand cannot assert a match by accident.

`FaceVerificationService` will only ever move a decision *toward* uncertainty. Nothing
downstream can upgrade an UNCERTAIN to a MATCH.

### Face authentication endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/face` | 1:1 verification — parts `document` and `live` |
| `POST` | `/api/face/enrolments` | Register a reference face for a known traveller |
| `GET` | `/api/face/enrolments` | List enrolments, newest first |
| `DELETE` | `/api/face/enrolments/{id}` | Withdraw an enrolment (never deleted) |
| `POST` | `/api/face/identify` | 1:N identification against the enrolled gallery |

### Watchlist management endpoints

| Method | Path | Purpose |
|---|---|---|
| `PUT` | `/api/watchlist/{id}` | Update an entry; lookup keys are rebuilt |
| `POST` | `/api/watchlist/{id}/reactivate` | Restore a withdrawn entry |
| `POST` | `/api/watchlist/import` | Bulk import; bad rows are reported, not fatal |
| `GET` | `/api/watchlist/export` | Every entry as JSON; round-trips through import |

### Paged responses

Paged endpoints return a `PagedModel` envelope — `content` plus a `page` object carrying
`size`, `number`, `totalElements` and `totalPages`.

### Rate limiting

Write requests to `/api/**` are capped per client. Reads are never limited — throttling an
officer's console mid-shift is a worse failure than the flood it would prevent. Responses
carry `X-RateLimit-Limit`, `X-RateLimit-Remaining` and `X-RateLimit-Reset`; exceeding the
cap returns `429` with an `ApiError` body.
