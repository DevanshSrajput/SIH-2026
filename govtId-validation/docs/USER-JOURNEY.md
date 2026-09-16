# User Journey & APIs — An Onboarding Guide

**Who this is for:** someone joining the project who has never seen it before. It walks
through what a real person does with this system, screen by screen, and shows exactly
which API is called at each step and what happens behind it.

If you want the terse API reference instead, read [API.md](API.md). If you want the
architecture, read [ARCHITECTURE.md](ARCHITECTURE.md). This document sits in between: it
tells the *story*, and hangs the APIs off it.

---

## 1. What the system actually does

Imagine an immigration desk at an airport. A traveller hands over a passport. The officer
has maybe forty seconds to answer three questions:

1. **Is this document real?** Or has someone printed, edited, or altered it?
2. **Is it valid?** Correct check digits, not expired, sane dates, a country code that exists.
3. **Is this the person it belongs to?** And is this person or document on a watchlist?

This platform automates the evidence-gathering for those questions and hands the officer a
**risk score**, a **recommended verdict**, and — crucially — a **list of reasons**. It never
makes the final call. The officer does. The system's job is to make the officer's decision
faster and defensible months later in an audit.

### Two people use it

| Persona | What they want | Where they live in the app |
|---|---|---|
| **Border officer** (the main user) | Screen a document, read the findings, record a decision | `Screen`, `Cases` |
| **Supervisor / analyst** | Maintain the watchlist, watch shift-level trends | `Watchlist`, `Dashboard` |

Those four words — Screen, Cases, Watchlist, Dashboard — are literally the four tabs in the
React console ([`App.jsx`](../frontend/src/App.jsx)). The whole product is those four
screens plus one case-detail page.

---

## 2. The journey at a glance

```
   OFFICER                  FRONTEND                    BACKEND
      |                         |                          |
  [1] presents document ──► Screen page ──► POST /api/screenings
      |                         |                          |── stores images (GridFS)
      |                         |                          |── Module 1  OCR
      |                         |                          |── Module 2  Validation
      |                         |                          |── Module 3  Tampering
      |                         |                          |── Module 4  Face
      |                         |                          |── Watchlist / history
      |                         |                          |── RiskEngine → verdict
      |                    result panel ◄──────────────────┘  (saved as a case)
      |
  [2] opens the case ─────► Case detail ──► GET /api/screenings/{ref}
      |                                 └─► GET /api/screenings/{ref}/audit
      |                                 └─► GET /api/screenings/{ref}/images/document
      |
  [3] decides ────────────► Decision box ─► POST /api/screenings/{ref}/decision
      |
  [4] later: browse ──────► Cases page ───► GET /api/screenings?page=0&size=25
      |
   SUPERVISOR
  [5] flags a document ───► Watchlist ────► POST /api/watchlist
  [6] reviews the shift ──► Dashboard ────► GET /api/stats?windowHours=24
```

Everything is under `/api`, unauthenticated today (it is meant to sit behind an
authenticating gateway — see [TASKS.md](TASKS.md)), and returns JSON. The one exception is
the image endpoint, which returns image bytes.

---

## 3. Step 1 — Screening a document

**Screen:** `Screen` tab → [`ScreenPage.jsx`](../frontend/src/pages/ScreenPage.jsx)

The officer fills a small form:

- **Document image** (required) — a photo or scan of the passport data page
- **Live capture** (optional) — a webcam photo of the traveller standing at the desk
- **Document type** — PASSPORT, VISA, NATIONAL_ID, DRIVING_LICENCE, PERMIT,
  TRAVEL_AUTHORIZATION, UNKNOWN. This is a *hint*, not a promise; the modules report what
  they actually find
- **Checkpoint / Lane / Officer id** — recorded on the case for the audit trail
- **Text** (optional) — text the caller already holds, e.g. an e-passport chip read or a
  keyed-in MRZ. When present it is *trusted over pixel OCR*, because a chip read is
  authoritative and a camera guess is not

Pressing **Screen** calls one API and waits for it.

### The API

```
POST /api/screenings?documentType=PASSPORT&checkpointId=T2&laneId=4&officerId=off-114
Content-Type: multipart/form-data
```

| Where | Name | Required | Meaning |
|---|---|---|---|
| form part | `document` | **yes** | The document image (JPEG/PNG), max 20 MB |
| form part | `live` | no | Live capture of the traveller — this is what enables Module 4 |
| query | `documentType` | no (`UNKNOWN`) | Document category hint |
| query | `checkpointId`, `laneId`, `officerId` | no | Recorded on the case and audit trail |
| query | `text` | no | Chip read / keyed MRZ, trusted over OCR |

Try it from a terminal:

```bash
curl -F "document=@passport.jpg" "http://localhost:8080/api/screenings?documentType=PASSPORT&officerId=off-114"
```

Handled by [`ScreeningController.screen()`](../backend/src/main/java/com/govid/screening/api/ScreeningController.java),
which does almost nothing itself — it validates that an image is present, converts the
upload to bytes, and hands a `ScreeningRequest` to `ScreeningService`.

### What happens inside (the part worth understanding)

[`ScreeningService.screen()`](../backend/src/main/java/com/govid/screening/pipeline/ScreeningService.java)
is the orchestrator. In order:

**0. Create and save the case first.** It mints a human-quotable reference like `BRD-FPW5DK`
(the alphabet excludes `I`, `O`, `0`, `1` so it survives being read aloud over a radio),
stores both images in GridFS, and saves the case with status `PROCESSING` — *before* any
analysis runs. If the process crashes mid-pipeline, the evidence is still recoverable
against that reference.

**1. Module 1 — OCR Extraction** (`ocr` package). Reads the document. Backends are pluggable
and ordered by `priority()`: supplied text (the chip read) beats Claude vision OCR beats
Tesseract. Produces `ExtractedFields` — surname, given names, document number, issuing
state, nationality, dates, sex, plus parsed MRZ (the two or three lines of `<<<` chevrons at
the bottom of a passport, ICAO 9303 formats TD1/TD2/TD3/MRV).

**2. Module 2 — Document Validation** (`validation`). Deterministic rule checks against the
issuing standard: MRZ check digits, data-page vs MRZ agreement, chronology (issued before
expiry, born before issued), country codes that actually exist, visa terms.

**3. Module 3 — Tampering Detection** (`tampering`). Image forensics — Error Level Analysis
for splices, EXIF metadata forensics (editing software, missing camera info, inconsistent
timestamps), noise consistency, copy-move duplication.

**4. Module 4 — Face Verification** (`face`). Compares the portrait on the document against
the live capture. Needs both an image *and* a configured face service.

**5. Watchlist & identity screening** (`watchlist`). Not a "module" numerically but a full
pipeline stage. Checks the document number and identity key against the blacklist, and also
looks *across past cases*: has this document been presented under a different identity? Has
this identity used multiple documents? Is this document being presented suspiciously often?

**6. `RiskEngine`** turns every finding into one score and a verdict.

### Four conventions that will confuse you if nobody explains them

These are the rules that make the codebase make sense. They're in
[CLAUDE.md](../CLAUDE.md) too, and each one exists for a reason.

**Extraction never judges.** An `OcrEngine` only *reads* pixels. It never decides whether a
document is fake. Every judgement lives in deterministic, explainable code in Modules 2–4,
so an officer can be told precisely which rule fired. Don't add "does this look forged?"
logic to an OCR backend.

**Never fabricate a measurement.** If no face matcher is configured, Module 4 reports
`SKIPPED` with a reason. It does *not* invent a plausible-looking 0.87 similarity. A
screening decision must never rest on a made-up number — that's why
`FaceVerificationService` deliberately has no built-in fallback matcher.

**Modules never throw into the pipeline.** A module that cannot run returns
`ModuleResult.skipped(...)` or `.failed(...)` so the remaining modules still produce a
decision. And then `RiskEngine` refuses to return `CLEAR` when a module *failed* — missing
evidence is not the same as absence of evidence, so the case gets referred to a human. A
*skipped* module does not trigger that; skipped means "not applicable here", failed means
"we tried and couldn't".

**Every finding carries evidence.** Each `RiskFlag` has an `evidence` map. That map is what
makes a finding auditable and re-checkable months later. Populate it.

### How the score is computed

[`RiskEngine`](../backend/src/main/java/com/govid/screening/risk/RiskEngine.java) combines
findings **multiplicatively**, not by adding points:

```
score = 100 × (1 − Π(1 − weightᵢ))
```

Severity weights: `INFO` 0, `LOW` 6, `MEDIUM` 15, `HIGH` 32, `CRITICAL` 70.

Three properties follow, and all three matter at a checkpoint:

- **It saturates.** A pile of minor observations can never out-score one decisive finding.
  Nobody gets rejected by accumulated trivia.
- **It is monotonic.** More evidence never lowers the score.
- **It is order-independent.** Modules can run in any order and the verdict doesn't move.

Score → band → verdict:

| Score | Band | Verdict | Meaning |
|---|---|---|---|
| 0–19 | LOW | `CLEAR` | No obstacle to entry on document grounds |
| 20–34 | MEDIUM | `CLEAR` | |
| 35–44 | MEDIUM | `REVIEW` | A human must inspect before deciding |
| 45–69 | HIGH | `REVIEW` | |
| 70–100 | CRITICAL | `REJECT` | Do not accept without supervisory review |

The `REJECT` (70) and `REVIEW` (35) thresholds are configurable
(`screening.risk.reject-threshold` / `review-threshold`).

### The response

You get back the whole `ScreeningCase`. The shape matters because every later screen reads
the same object:

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
    "engine": "supplied-text",
    "ocrConfidence": 0.99,
    "mrz": { "format": "TD3", "checkDigits": { "dateOfBirth": false } }
  },
  "moduleResults": [
    { "module": "DOCUMENT_VALIDATION", "status": "COMPLETED", "durationMillis": 2,
      "flags": [ /* ... */ ], "details": { /* ... */ } }
  ],
  "risk": {
    "score": 75,
    "band": "CRITICAL",
    "verdict": "REJECT",
    "flags": [
      { "code": "MRZ_CHECKDIGIT_MISMATCH",
        "module": "DOCUMENT_VALIDATION",
        "severity": "HIGH",
        "message": "The MRZ check digit for the date of birth does not match ...",
        "evidence": { "field": "dateOfBirth", "mrzFormat": "TD3" } }
    ],
    "topReasons": ["..."],
    "explanation": "Risk score 75 from 5 finding(s) including 1 critical ..."
  }
}
```

- Case `status`: `RECEIVED` → `PROCESSING` → `COMPLETED` or `FAILED`
- Module `status`: `COMPLETED` | `SKIPPED` | `FAILED`
- `explanation` is a one-paragraph plain-English rationale written by `RiskEngine`. It names
  what drove the score, so the recommendation can be **challenged** rather than merely obeyed.

The officer sees the verdict banner, the score, and the findings immediately on the Screen
page, without navigating anywhere.

---

## 4. Step 2 — Reading the case in detail

**Screen:** `Cases` → click a row → [`CaseDetailPage.jsx`](../frontend/src/pages/CaseDetailPage.jsx)

This page fires three calls. The first two run in parallel on load:

```
GET /api/screenings/{reference}          → the full case
GET /api/screenings/{reference}/audit    → the investigation trail
```

and the images are loaded by the browser as plain `<img src>`:

```
GET /api/screenings/{reference}/images/document
GET /api/screenings/{reference}/images/live
```

`{reference}` accepts either the human reference (`BRD-FPW5DK`) or the internal Mongo id —
`ScreeningController.get()` tries `findByCaseReference` first and falls back to `findById`.
Unknown reference gives `400`. The image endpoint returns `404` if that image was never
stored (very common for `live`, which is optional), and the audit call is deliberately
wrapped in `.catch(() => [])` on the frontend so a missing trail never blanks the page.

The page lays out: extracted identity, per-module status, the findings list with each flag's
evidence, the stored evidence images, the audit trail, and the officer decision box.

---

## 5. Step 3 — Recording the officer's decision

Still on the case detail page. Three buttons — Clear, Review, Reject — plus notes.

```
POST /api/screenings/{reference}/decision
Content-Type: application/json

{ "decision": "REVIEW", "officerId": "off-114", "notes": "Referred for secondary inspection" }
```

`decision` must be `CLEAR`, `REVIEW` or `REJECT`.

**The important bit:** the system recommendation is *never overwritten*. `recordDecision()`
writes `officerDecision`, `officerNotes`, `decidedAt` alongside the machine's `risk.verdict`,
and both are kept. A divergence between them — the machine said REJECT, the officer said
CLEAR — is exactly what a later review needs to see. An `AuditEvent` records the decision,
the system recommendation and the risk score together.

---

## 6. Step 4 — Browsing past cases

**Screen:** `Cases` tab → [`CasesPage.jsx`](../frontend/src/pages/CasesPage.jsx)

```
GET /api/screenings?page=0&size=25
```

Returns a Spring `Page` of `CaseSummary` rows, newest first (`size` is capped at 100).
`CaseSummary` is deliberately a small projection — reference, name, nationality, document
number, score, band, verdict, officer decision, flag count, checkpoint, timing — *not* the
full case with all its module results and evidence maps. The list has to stay fast when
thousands of cases pile up during a shift.

---

## 7. Step 5 — Maintaining the watchlist

**Screen:** `Watchlist` tab → [`WatchlistPage.jsx`](../frontend/src/pages/WatchlistPage.jsx)

```
GET    /api/watchlist?page=0&size=50     list entries, newest first
POST   /api/watchlist                    add an entry
DELETE /api/watchlist/{id}?actor=off-114 deactivate an entry
```

Adding an entry:

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

`listType`: `STOLEN_DOCUMENT`, `REVOKED_DOCUMENT`, `ENTRY_BAN`, `WANTED`, `VISA_OVERSTAY`,
`LOCAL_INTEREST`. `severity` defaults to `CRITICAL`.

Two design decisions you'll trip over otherwise:

**You must supply a document number, *or* a surname together with a date of birth.** A name
on its own is rejected with `400` — names are far too common to match on safely, and a
watchlist that fires on every "Ali" or "Silva" is a watchlist officers learn to ignore.

**Name parts are taken separately**, not as one display string, because the identity key is
built from surname + date of birth. Guessing which word is the surname would create entries
that silently never match anything.

**`DELETE` does not delete.** It sets `active = false`. A watchlist is evidence — removing
the row would erase the record that a document was ever flagged, and with it the reason any
past case was rejected. Both the add and the deactivate write an `AuditEvent`.

---

## 8. Step 6 — The supervisor's dashboard

**Screen:** `Dashboard` tab → [`DashboardPage.jsx`](../frontend/src/pages/DashboardPage.jsx)

```
GET /api/stats?windowHours=24
```

Answers the questions a supervisor actually asks during a shift: how many people came
through, how many were referred, how long screening is taking, and **which findings are
driving referrals right now** — a spike in one flag code is often the first sign of a batch
of forgeries circulating.

Returns `totalScreenings`, `totalAllTime`, `referredForReview`, `referralRate`,
`medianProcessingMillis`, `slowestProcessingMillis`, `byVerdict`, `byDocumentType`,
`topFlags` (the ten most frequent finding codes) and `highestRiskCases` (top five).

Processing time is a **median**, not a mean, so one pathological 30-second image can't
distort the number a supervisor uses to judge whether the lanes are keeping up.

---

## 9. Complete API table

| Method | Path | Used by | Purpose |
|---|---|---|---|
| `POST` | `/api/screenings` | Screen page | Run the pipeline on one document |
| `GET` | `/api/screenings` | Cases page | Paged case summaries, newest first |
| `GET` | `/api/screenings/{ref}` | Case detail | One case in full |
| `GET` | `/api/screenings/{ref}/audit` | Case detail | Append-only audit trail, oldest first |
| `GET` | `/api/screenings/{ref}/images/{kind}` | Case detail | Evidence image; `kind` = `document` \| `live` |
| `POST` | `/api/screenings/{ref}/decision` | Case detail | Record the officer's determination |
| `GET` | `/api/watchlist` | Watchlist page | Paged entries |
| `POST` | `/api/watchlist` | Watchlist page | Add an entry |
| `DELETE` | `/api/watchlist/{id}` | Watchlist page | **Deactivate** (never deletes) |
| `GET` | `/api/stats` | Dashboard | Shift statistics over a window |

Errors are uniform, from `GlobalExceptionHandler`:

```json
{ "timestamp": "2026-08-28T00:17:21Z", "status": 400, "error": "Bad Request",
  "message": "A document image is required." }
```

| Status | Cause |
|---|---|
| `400` | Missing document image, unknown case reference, invalid watchlist entry |
| `413` | Upload exceeds the 20 MB limit |
| `500` | Unexpected error — note the pipeline contains module failures rather than propagating them, so this is rare |

The frontend's [`api.js`](../frontend/src/api.js) surfaces the server's own `message` rather
than a generic one, because "the image is too large" and "the service is down" demand
different responses at the desk.

---

## 10. Glossary

| Term | Meaning |
|---|---|
| **MRZ** | Machine Readable Zone — the `<<<`-filled lines at the bottom of a passport. ICAO 9303 defines formats TD1/TD2/TD3 (and MRV for visas). Contains name, document number, nationality, dates, and check digits |
| **Check digit** | A digit computed from the other characters. If it doesn't match, someone edited the field — this is the single most reliable forgery signal there is |
| **ELA** | Error Level Analysis — re-compresses a JPEG and looks for regions that respond differently, which suggests a spliced-in patch |
| **Copy-move** | A forgery where part of the image is duplicated elsewhere in the same image |
| **`RiskFlag`** | One finding: a `code`, the `module` that raised it, a `severity`, a human `message`, and an `evidence` map |
| **`ModuleResult`** | One module's output: status, duration, its flags, and a details map |
| **`RiskAssessment`** | The combined result: score, band, verdict, all flags, top reasons, explanation |
| **Verdict** | `CLEAR` / `REVIEW` / `REJECT` — a *recommendation*. The officer decides |
| **Identity key** | Normalised surname + date of birth, used to link cases across documents (`support/IdentityKeys`) |
| **`NameNormaliser`** | Handles ICAO transliteration (`Ü`→`UE`, `ß`→`SS`). All name comparison goes through it — stripping diacritics alone would raise a forgery finding against every traveller with a non-English name |

---

## 11. Running it yourself

This machine has no Docker daemon and no local `mongod`, so the embedded profile is the
working default:

```bash
cd backend && ./mvnw spring-boot:run -Pembedded-mongo
```

With MongoDB available, use Docker and the plain run instead:

```bash
docker compose up -d mongo
```

Frontend (proxies `/api` to `localhost:8080`):

```bash
cd frontend && npm run dev
```

Tests:

```bash
cd backend && ./mvnw test
```

There's also a Postman collection in [`postman/`](../postman) if you'd rather click than curl.

### A suggested first hour

1. Start the backend with `-Pembedded-mongo` and the frontend with `npm run dev`.
2. Screen any passport image on the `Screen` tab. Read the findings — the evidence maps are
   the interesting part.
3. Screen the *same* document again, then a second time with a different name in the `text`
   field. Watch the watchlist stage raise `DOCUMENT_USED_BY_OTHER_IDENTITY`.
4. Add that document number to the `Watchlist`, screen it once more, and see the verdict
   jump to `REJECT`.
5. Open the case, record a decision that *disagrees* with the machine, and look at the audit
   trail — both views are preserved.
6. Check the `Dashboard`.

### Where to read next

- [ARCHITECTURE.md](ARCHITECTURE.md) — the module layout
- [DESIGN.md](DESIGN.md) — **read this before changing anything structural.** It records the
  rejected alternatives, so you can tell a deliberate decision from an accident
- [DETECTION.md](DETECTION.md) — read before touching any threshold in `tampering`. Identity
  documents are covered in deliberately repeated security printing, and every naive forensic
  threshold fires on it. Each guard is there because it was needed
- [RISK-SCORING.md](RISK-SCORING.md) — the scoring model in full
- [DATA-MODEL.md](DATA-MODEL.md) — the Mongo collections
