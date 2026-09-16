# AI-Based Fake Identity & Document Screening System

An automated screening platform for border checkpoints. It reads identity and travel
documents, checks them against issuing standards, looks for signs of tampering, verifies
the bearer against the document portrait, screens against watchlists and case history, and
returns a risk score with a written rationale an officer can challenge.

**Status:** working end-to-end. Backend, database, screening pipeline and officer console
all run; 59 tests pass. Face matching is a contract awaiting a biometric service (see
[Known limitations](#known-limitations)).

---

## What it does

```
                    ┌──────────────────────────────────────┐
  document image →  │ Module 1  OCR Extraction             │ → structured fields
                    ├──────────────────────────────────────┤
                    │ Module 2  Document Validation        │ ┐
                    │ Module 3  Tampering Detection        │ ├→ findings
                    │ Module 4  Face Verification          │ │
                    │           Watchlist & identity history│ ┘
                    └──────────────────────────────────────┘
                                      ↓
                             Risk engine → score 0-100
                                      ↓
                        CLEAR · REVIEW · REJECT + rationale
```

Each module returns findings independently; none can abort the run. The risk engine
combines them into one score and a recommended action. The officer's own determination is
recorded separately and never overwritten.

### Measured behaviour

Two documents through the live API — the same passport, one of them forged by altering the
MRZ date of birth and pasting a duplicated region onto the page:

| Document | Score | Verdict | Findings |
|---|---|---|---|
| Genuine | 6 | CLEAR | `META_NO_CAMERA_INFO` (informational) |
| Forged | 75 | REJECT | `MRZ_CHECKDIGIT_MISMATCH`, `MRZ_COMPOSITE_MISMATCH`, `DOCUMENT_USED_BY_OTHER_IDENTITY`, `COPY_MOVE_DUPLICATION`, `META_NO_CAMERA_INFO` |

Screening takes ~400–700 ms per document, almost all of it image forensics.

---

## Running it

**Prerequisites:** Java 21+ and Node 20+. MongoDB is optional — see below.

### 1. Start the API

With Docker available:

```bash
docker compose up -d mongo
```

```bash
cd backend && ./mvnw spring-boot:run
```

Without Docker or a local `mongod`, use the embedded-database profile instead — no
infrastructure at all, data discarded on exit:

```bash
cd backend && ./mvnw spring-boot:run -Pembedded-mongo
```

The API listens on <http://localhost:8080>; health at `/actuator/health`.

### 2. Start the officer console

```bash
cd frontend && npm install && npm run dev
```

Open <http://localhost:5173>. The dev server proxies `/api` to the backend.

### 3. Screen a document

```bash
curl -F "document=@passport.jpg" -F "text=<mrz.txt" "http://localhost:8080/api/screenings?documentType=PASSPORT"
```

`text` is optional — it carries an e-passport chip read or a keyed MRZ, and is trusted over
pixel OCR when present. See [docs/API.md](docs/API.md).

### Tests

```bash
cd backend && ./mvnw test
```

---

## Optional integrations

Everything below is off by default; the platform runs fully without any of it.

| Capability | Enable by | Effect |
|---|---|---|
| Vision OCR | Set an Anthropic credential in the environment | `ClaudeVisionOcrEngine` activates and reads document images directly |
| Classical OCR | Install Tesseract on `PATH` | `TesseractOcrEngine` activates as an offline reader |
| Face matching | Set `screening.face.service-url` | Module 4 activates against your biometric service |

With none of them, Module 1 needs the MRZ supplied with the request and Module 4 reports
that it did not run. Modules 2 and 3 are fully self-contained.

---

## Documentation

| Document | Contents |
|---|---|
| [docs/SPECIFICATION.md](docs/SPECIFICATION.md) | **What** the system must do: each module, the fields extracted, expected impact |
| [docs/DESIGN.md](docs/DESIGN.md) | **How and why** it is built: design goals, component design, decisions and rejected alternatives |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Short orientation: components, request flow, extension points |
| [docs/USER-JOURNEY.md](docs/USER-JOURNEY.md) | Onboarding walkthrough: what a user does, screen by screen, and the API behind each step |
| [docs/DETECTION.md](docs/DETECTION.md) | How each forensic technique works, and what defeats it |
| [docs/RISK-SCORING.md](docs/RISK-SCORING.md) | The scoring model and the full finding catalogue |
| [docs/DATA-MODEL.md](docs/DATA-MODEL.md) | MongoDB collections and indexes |
| [docs/API.md](docs/API.md) | REST reference and configuration |
| [docs/TESTING.md](docs/TESTING.md) | Test strategy, what each suite guards, and the gaps |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Profiles, capability matrix, monitoring, production gates |
| [docs/TASKS.md](docs/TASKS.md) | Delivered work and the remaining roadmap |
| [CLAUDE.md](CLAUDE.md) | Conventions for anyone (or any agent) working in this repo |

New to the repo? Read **SPECIFICATION** for the problem, then **DESIGN** for the reasoning.

---

## Known limitations

These are stated plainly because a screening platform that overstates its own certainty is
worse than one that does less.

- **Face verification is a contract, not an implementation.** No similarity score is
  invented when no matcher is configured; Module 4 reports that it did not run. Wiring a
  biometric service in is a configuration change, not a code change.
- **Copy-move detection weakens on heavily re-compressed images.** A pasted region whose
  position sits at a different phase relative to the JPEG grid than its source compresses
  differently. Pre-match smoothing recovers most of this, but a heavily degraded scan can
  still hide a paste.
- **Error Level Analysis needs a lossy original.** On a PNG or a losslessly stored scan the
  technique has nothing to measure and reports nothing rather than guessing.
- **No authentication yet.** The API is unauthenticated. It must sit behind an
  authenticating gateway before any deployment. See [docs/TASKS.md](docs/TASKS.md).
- **Watchlists are local.** There is no INTERPOL SLTD or national-register integration; the
  watchlist is populated through the API.
