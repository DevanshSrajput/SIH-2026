# CLAUDE.md

AI-Based Fake Identity & Document Screening System — a border-checkpoint platform that
reads identity and travel documents, checks them against issuing standards, looks for
tampering, verifies the bearer, and returns a risk score with an auditable rationale.

Full documentation is in [docs/](docs/). Start with
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for a quick orientation, and read
[docs/DESIGN.md](docs/DESIGN.md) before changing anything structural — it records the
rejected alternatives, so you can tell a deliberate decision from an accident.

## Stack

| Layer | Technology |
|---|---|
| Frontend | React 19 + Vite — officer console |
| Backend | Java 21 on Spring Boot 4.1.1 |
| Database | MongoDB (Spring Data), GridFS for evidence images |
| Build | Maven wrapper (`backend/mvnw`) — no system Maven installed |

## Commands

Run the API. This machine has no Docker daemon and no local `mongod`, so the embedded
profile is the working default here:

```bash
cd backend && ./mvnw spring-boot:run -Pembedded-mongo
```

With MongoDB available, use Docker and the plain run instead. The compose file is at the
**repository root**, one level above this directory:

```bash
cd .. && docker compose up -d mongo
```

The whole stack in containers, including the face service:

```bash
cd .. && docker compose up --build
```

Tests:

```bash
cd backend && ./mvnw test                            # 88
cd ../../face-verification-service && pytest -q      # 58
```

Frontend:

```bash
cd frontend && npm run dev
```

Face service (Module 4; without it Module 4 reports SKIPPED):

```bash
cd ../face-verification-service
python download_models.py    # first run only, ~40MB
python app.py
export FACE_SERVICE_URL=http://localhost:5000
```

## Architecture

Four modules plus a cross-case watchlist stage, orchestrated by `ScreeningService`. Each
returns a `ModuleResult` holding `RiskFlag`s; `RiskEngine` combines every flag into one
`RiskAssessment`.

```
upload → Module 1 OCR → Module 2 Validation → Module 3 Tampering
                                            → Module 4 Face
                                            → Watchlist / identity history
                                            → RiskEngine → Verdict
```

| Package | Responsibility |
|---|---|
| `ocr` | Module 1. `OcrEngine` backends + `MrzParser` (ICAO 9303 TD1/TD2/TD3/MRV) |
| `validation` | Module 2. Check digits, chronology, country codes, visa terms |
| `tampering` | Module 3. `TamperingDetector` implementations (ELA, metadata, noise, copy-move) |
| `face` | Module 4. `FaceVerifier` contract, HTTP delegate, enrolment and 1:N identification |
| `watchlist` | Blacklist hits, multiple-identity and document-reuse detection |
| `risk` | Score aggregation and verdict |
| `support` | `IdentityKeys`, `NameNormaliser` — key normalisation |
| `domain` | Mongo documents and value records |

## Conventions that matter here

**Extraction and adjudication stay separate.** OCR backends only *read*. They never decide
whether a document is genuine. Every judgement is made by deterministic, explainable code
in Modules 2–4 so an officer can be told exactly which rule fired. Do not add "is this
fake?" reasoning to an `OcrEngine`.

**Never fabricate a measurement.** If a capability is not configured — no face matcher, no
OCR engine — the module reports `SKIPPED`/`FAILED` with a reason. It does not estimate a
plausible-looking number. A screening decision must not rest on an invented score. This is
why `FaceVerificationService` has no built-in fallback matcher.

**Modules never throw into the pipeline.** A module that cannot run returns
`ModuleResult.skipped(...)` or `.failed(...)` so the remaining modules still produce a
decision. `RiskEngine` then refuses to return `CLEAR` on a *failed* module — missing
evidence is not absence of evidence. A *skipped* module does not trigger this.

**Risk combines multiplicatively, not additively** (`RiskEngine`). Accumulated trivia must
never outweigh one decisive finding.

**Every `RiskFlag` carries `evidence`.** The map is what makes a finding auditable and
re-checkable months later. Populate it.

**Pluggable backends are ordered by `priority()`** and each declares its own
`isAvailable()`. Adding a backend means adding a `@Component` — no caller changes.

**Detector thresholds exist to survive real documents.** Identity documents are covered in
deliberately repeated security printing, and every naive forensic threshold fires on it.
Before loosening any guard in `tampering`, read [docs/DETECTION.md](docs/DETECTION.md) —
each one is there because it was needed.

**Name comparison goes through `NameNormaliser`.** ICAO transliteration expands characters
(`Ü`→`UE`, `ß`→`SS`); stripping diacritics alone would raise a forgery finding against
every traveller with a non-English name.

**Face comparison has three answers, not two.** `FaceDecision` is MATCH / NO_MATCH /
UNCERTAIN. A positive identification needs a decisive score *and* two usable images *and*
a passed liveness check *and* one face in frame *and* landmark-aligned crops. Anything
else is UNCERTAIN with `blockers` saying why. Nothing in the pipeline may upgrade an
UNCERTAIN to a MATCH — `FaceVerificationService` deliberately only ever moves a decision
*toward* uncertainty.

**Face thresholds are raw cosine, never rescaled.** Two different people score 0.00-0.25;
the same person across a scan and a live capture scores 0.40-0.75. SFace's published
break-even is 0.363 and `match-threshold` sits above it at 0.46. Never map the cosine
onto [0, 1] to make it look like a percentage — doing that compresses the two populations
together and was the original false-accept bug. Re-calibrate with
`face-verification-service/calibrate.py` against real captures before trusting any number
here.

## Environment gotchas

- **Spring Boot 4 uses Jackson 3** (`tools.jackson`). There is no `com.fasterxml`
  `ObjectMapper` bean, and Jackson 2 config keys such as `write-dates-as-timestamps` fail
  at startup. Use Jackson 3 in application code; the Anthropic SDK carries its own
  Jackson 2 internally.
- **`src/test/resources/application.yml` shadows the main one** on the test classpath, so
  a broken main config can still pass tests. Verify config changes by starting the app.
- **The Bash tool needs Windows-style paths for curl `@file` arguments** (`C:/Users/...`,
  not `/c/Users/...`).
- **YuNet's detection row is `[x, y, w, h, 10 landmark coords, score]`** — the score is
  the *last* element, not index 4. Reading index 4 takes the right eye's x-coordinate
  (scores come back as 233 instead of 0.71) and shifts every landmark by one slot, which
  feeds `alignCrop` a face warped to the wrong canonical position. That silently degrades
  every embedding. Pinned by `TestYuNetRowLayout`.
- **SFace takes raw BGR with no mean subtraction.** It is not a Caffe classifier. Passing
  the face *detector's* mean (104, 177, 123) shifts every embedding by a constant.
- **JDK 27 is installed at `C:\Program Files\Java\jdk-27`** but `JAVA_HOME` is not set,
  so `./mvnw` fails until you export it.

## Optional integrations

All off by default; the system runs end-to-end without any of them.

- **Claude vision OCR** (`ClaudeVisionOcrEngine`) — activates when an Anthropic credential
  is present. Model `claude-opus-5` via the official `com.anthropic:anthropic-java` SDK.
- **Face matching** (`HttpFaceVerifier`) — activates when `screening.face.service-url`
  points at a service exposing `POST /compare`. Left empty on purpose: an unset URL makes
  Module 4 report SKIPPED, whereas a default pointing at a service that happens to be down
  would make it FAILED, and `RiskEngine` refuses CLEAR on a failed module — every
  screening on a machine without the face service would be referred. `start.ps1` sets
  `FACE_SERVICE_URL` when it brings the service up.
- **Face enrolment** (`FaceEmbeddingClient`) — 1:N identification against a MongoDB
  registry of embeddings. Needs the same service URL; the model lives in the Python
  service and the records live in Mongo.
- **Tesseract** (`TesseractOcrEngine`) — tries the configured path first, then the
  standard install locations for the platform. No `PATH` editing needed.
