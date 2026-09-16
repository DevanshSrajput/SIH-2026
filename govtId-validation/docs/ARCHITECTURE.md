# Architecture

## Stack

| Layer | Choice | Why |
|---|---|---|
| Officer console | React 19 + Vite | Fast reload during development; the console is a thin view over the API |
| API | Java 21, Spring Boot 4.1 | Matches the installed JDK 25 runtime; Spring Data gives Mongo mapping and GridFS free |
| Store | MongoDB | A screening case is one deeply nested document read and written whole; findings vary in shape per module, which relational columns model badly |
| Evidence | GridFS | Images live beside the case, so a screening can be re-run during an investigation |
| Build | Maven wrapper | No system Maven required |

## Request flow

```
POST /api/screenings  (multipart: document, optional live capture, optional MRZ text)
        │
        ├─ ScreeningService
        │     1. create case, assign reference (BRD-XXXXXX)
        │     2. store evidence images in GridFS  ← before analysis, so a crash still
        │                                            leaves the images recoverable
        │     3. persist the case
        │
        ├─ Module 1  OcrService ──────────► ExtractedFields
        │     picks highest-priority available OcrEngine
        │     parses MRZ (MrzParser), then printed labels
        │
        ├─ Module 2  DocumentValidationService ──► findings
        ├─ Module 3  TamperingService ───────────► findings   (4 detectors)
        ├─ Module 4  FaceVerificationService ────► findings
        ├─          WatchlistService ────────────► findings   (blacklist + case history)
        │
        ├─ RiskEngine  → score, band, verdict, rationale
        └─ persist completed case + audit event
```

Modules run in dependency order because later stages need Module 1's fields. Each returns a
`ModuleResult` rather than throwing, so one failure never costs the whole screening.

## Packages

```
com.govid.screening
├── api          REST controllers, DTOs, error handling
├── config       Clock bean, CORS
├── domain       Mongo documents and value records
├── face         Module 4: FaceVerifier contract + HTTP delegate
├── ocr          Module 1: OcrEngine backends, MrzParser, field extraction
├── pipeline     ScreeningService orchestration, ImageStore
├── repository   Spring Data Mongo repositories
├── risk         RiskEngine
├── support      IdentityKeys, NameNormaliser
├── tampering    Module 3: TamperingDetector implementations
├── validation   Module 2: rules and country codes
└── watchlist    Blacklist and cross-case identity screening
```

## Design decisions

### Extraction is separated from adjudication

OCR backends only *read*. They are never asked whether a document is genuine. Every
judgement is made by deterministic code in Modules 2–4.

This matters most for the vision-model backend. A model asked "is this fake?" produces an
answer that cannot be audited, reproduced or appealed. A model asked only to transcribe
produces text whose errors surface downstream as a failed check digit — a visible,
explainable finding rather than a silently wrong verdict.

### Capabilities are pluggable and self-declaring

`OcrEngine` and `FaceVerifier` implementations declare their own `isAvailable()`, and OCR
engines are ordered by `priority()`. Adding a backend means adding a `@Component`; no
caller changes. This is why the platform runs identically with Tesseract, with a vision
model, with a chip reader, or with none of them.

### Nothing is ever estimated to fill a gap

If a capability is unconfigured, its module reports `SKIPPED` or `FAILED` with a reason. It
does not produce a plausible-looking number. `FaceVerificationService` deliberately has no
built-in fallback matcher for this reason.

The risk engine then refuses to return `CLEAR` when a module *failed*: missing evidence is
not absence of evidence, and a case cannot be cleared on an examination that did not finish.

### Findings carry their evidence

Every `RiskFlag` has a `code`, a `severity`, an officer-facing `message` and an `evidence`
map holding the actual values the rule compared. The evidence is what makes a finding
auditable and re-checkable months later, and it is what the console renders beneath each
finding.

### Risk combines multiplicatively

`score = 100 × (1 − Π(1 − wᵢ))`. Saturating, monotonic and order-independent — so
accumulated trivia can never outweigh one decisive finding, and the modules can run in any
order without moving the verdict. See [RISK-SCORING.md](RISK-SCORING.md).

### The audit trail is append-only

Screenings, officer decisions and watchlist changes all write `AuditEvent` records.
Watchlist entries are deactivated, never deleted: removing a row would erase the reason a
past case was rejected.

## Extension points

| To add | Implement | Notes |
|---|---|---|
| A new OCR backend | `OcrEngine` | Set `priority()` below the engine it should outrank |
| A new forensic technique | `TamperingDetector` | Return an empty list when it cannot run; never throw |
| A face matcher | `FaceVerifier` | Or point `screening.face.service-url` at an HTTP service |
| A new validation rule | Add to `DocumentValidationService` | Give it a stable code and populate `evidence` |
| A new document type | `DocumentType` enum + rules in Module 2 | MRZ parsing is already layout-generic |

## Environment notes

- **Spring Boot 4 ships Jackson 3** (`tools.jackson`). The `com.fasterxml` `ObjectMapper`
  bean no longer exists, and Jackson 2 settings such as `write-dates-as-timestamps` are
  rejected at startup. Application code uses Jackson 3; the Anthropic SDK keeps its own
  Jackson 2 internally.
- **`-Pembedded-mongo`** runs the API against an in-process MongoDB for development on a
  machine with no Docker daemon and no local `mongod`. Development only — case history is
  evidence and must not live in a process that discards it.
