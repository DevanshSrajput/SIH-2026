# Software Design Document

Detailed design for the AI-Based Fake Identity & Document Screening System.

[SPECIFICATION.md](SPECIFICATION.md) states *what* the system must do;
[ARCHITECTURE.md](ARCHITECTURE.md) is a short orientation. This document states *how* it is
built and, more importantly, **why it is built this way and what was rejected**.

---

## 1. Design goals

Ordered. Where two conflict, the higher one wins — this ordering is the single most
important design decision in the system.

| # | Goal | Consequence |
|---|---|---|
| 1 | **Every decision must be explainable** | No judgement is made by an opaque model. Each finding names a rule, carries the values it compared, and can be recomputed months later |
| 2 | **Never claim more certainty than the evidence supports** | An unconfigured capability reports that it did not run; it never estimates a plausible number |
| 3 | **A partial failure must still produce a usable answer** | Modules cannot abort the pipeline; a document with an unreadable image still gets screened on everything else |
| 4 | **Usable on real documents** | Thresholds are shaped by what genuine passports actually look like, not by what is clean in theory |
| 5 | **Fast enough for a queue** | Sub-second per document |
| 6 | **Auditable after the fact** | Evidence images and an append-only trail retained per case |

### Design constraints

- **The officer decides, the system recommends.** Legally and operationally, the platform
  cannot be the decision-maker. This shapes the data model (both verdicts stored) and the
  UI (rationale always visible).
- **False positives are expensive.** A checkpoint that refers 30% of travellers is worse
  than no system, because officers stop reading the referrals. This drove almost all
  detector tuning.
- **False negatives are also expensive**, but are mitigated by the officer still being
  present. This asymmetry justifies tuning towards precision over recall on the
  image-forensics detectors, while keeping the deterministic Module 2 checks maximally
  sensitive — those have essentially no false-positive rate.

---

## 2. System decomposition

```
┌─────────────────────────────────────────────────────────────┐
│  Officer console (React)                                    │
└────────────────────────┬────────────────────────────────────┘
                         │ HTTP/JSON, multipart upload
┌────────────────────────▼────────────────────────────────────┐
│  api          ScreeningController · WatchlistController      │
│               StatsController · GlobalExceptionHandler       │
├─────────────────────────────────────────────────────────────┤
│  pipeline     ScreeningService  (orchestration)              │
│               ImageStore        (GridFS evidence)            │
├─────────────────────────────────────────────────────────────┤
│  ocr        validation      tampering      face    watchlist │
│  Module 1   Module 2        Module 3       Module 4          │
├─────────────────────────────────────────────────────────────┤
│  risk         RiskEngine        (aggregation)                │
│  support      IdentityKeys · NameNormaliser                  │
├─────────────────────────────────────────────────────────────┤
│  repository   Spring Data Mongo   →   MongoDB + GridFS       │
└─────────────────────────────────────────────────────────────┘
```

Dependencies point strictly downward. No module knows about another module; each depends
only on `domain` and its own package. The orchestrator is the only component that knows the
pipeline exists, which is what lets modules be reordered, parallelised or removed without
touching each other.

---

## 3. Component design

### 3.1 `pipeline.ScreeningService`

The only stateful coordinator. Responsibilities:

1. Allocate a case and a human-quotable reference.
2. **Persist evidence images before analysing them**, so a crash mid-pipeline still leaves
   the images recoverable against the reference.
3. Run the five stages in dependency order.
4. Hand the collected `ModuleResult`s to `RiskEngine`.
5. Persist the completed case and write an audit event — *whatever happened*, including a
   failed run, because a screening that failed halfway is itself something an investigator
   may need to see.

```java
public ScreeningCase screen(ScreeningRequest request)
public ScreeningCase recordDecision(String caseId, Verdict decision, String officerId, String notes)
```

The whole module sequence is wrapped in a single `catch (RuntimeException)` that marks the
case `FAILED` and still persists it. This is the outermost safety net; the inner modules are
expected never to reach it.

### 3.2 Module contracts

Every module returns the same envelope:

```java
record ModuleResult(
    ScreeningModule module,
    Status status,          // COMPLETED | SKIPPED | FAILED
    long durationMillis,
    List<RiskFlag> flags,
    Map<String, Object> details,
    String note)
```

**Why one envelope for four very different modules.** The risk engine consumes findings
uniformly, the console renders module status uniformly, and adding a sixth stage requires no
change to either. `details` carries module-specific diagnostics — which detectors ran, what
the medians were — so a module can explain *why nothing fired*, not merely that nothing did.

**`SKIPPED` versus `FAILED` is a load-bearing distinction.** Skipped means "not applicable
or not configured" (no live capture, so no face check). Failed means "should have worked,
did not". `RiskEngine` refuses to return `CLEAR` on a failed module but tolerates a skipped
one. Collapsing these into one status would either block every screening that lacks a live
capture, or clear documents whose OCR silently died.

### 3.3 `ocr` — Module 1

```
OcrService.extract(OcrRequest)
   ├─ select first engine where isAvailable(), ordered by priority()
   │      10  SuppliedTextOcrEngine   chip read / keyed MRZ
   │      20  ClaudeVisionOcrEngine   credential present
   │      30  TesseractOcrEngine      binary on PATH
   ├─ engine.read()  →  raw text
   ├─ LabelledFieldExtractor on a *fresh* ExtractedFields  →  printed-only values
   ├─ MrzParser.parseFromText()  →  MRZ values (these win)
   └─ LabelledFieldExtractor again, filling only what the MRZ left empty
```

**The double extraction is deliberate and is the design's key idea.** Printed values are
captured *before* MRZ values overwrite anything, and stored in parallel fields
(`printedSurname`, `printedDateOfBirth`, …). A genuine document states its identity twice;
capturing both readings separately is what makes the Module 2 cross-check possible. Storing
one merged value would destroy the signal permanently.

**Engine selection is by priority, not configuration.** Each engine answers
`isAvailable(request)` for itself — text supplied, credential present, binary installed.
Deployment differences are therefore expressed by what is installed, not by a config matrix
that can disagree with reality.

**`MrzParser` is a static, dependency-free parser.** It has no Spring annotations and no
collaborators, because it encodes a published standard and must be trivially unit-testable
against the ICAO specimens.

### 3.4 `validation` — Module 2

A single service running six independent check groups, each appending to one flag list.
Stateless apart from an injected `Clock`.

**`Clock` is injected rather than calling `LocalDate.now()`.** Expiry and validity rules are
the core of this module and are untestable against a moving "today".

Cross-check comparison routes through `support.NameNormaliser` (§3.8) rather than a local
string compare — see that section for why this is not a detail.

### 3.5 `tampering` — Module 3

```
TamperingService.analyse(bytes, contentType)
   ├─ decode once  →  ImageEvidence(bytes, contentType, BufferedImage)
   ├─ for each TamperingDetector:  analyse(evidence)
   │     MetadataForensicsDetector · ErrorLevelAnalysisDetector
   │     NoiseConsistencyDetector  · CopyMoveDetector
   └─ corroboration: ≥3 distinct detectors with a MEDIUM+ finding
```

**Detectors are `@Component`s discovered by list injection.** Adding a technique is adding a
class. `TamperingService` catches `RuntimeException` per detector so one broken technique
cannot cost the other three.

**Corroboration counts detectors, not findings.** Two findings from one detector are one
technique's opinion stated twice; counting them as independent manufactures agreement the
evidence does not support. Low-severity observations — notably absent metadata — are
excluded entirely from the count.

The techniques themselves, and what defeats each, are in [DETECTION.md](DETECTION.md).

### 3.6 `face` — Module 4

```java
interface FaceVerifier {
    boolean isAvailable();
    FaceMatchResult compare(byte[] documentPortrait, byte[] liveCapture);
}
```

**Two thresholds, not one.** Above `match-threshold` accepted, below `mismatch-threshold`
treated as different people, between them referred rather than decided. Biometric comparison
is probabilistic and a single cutoff would force a confident answer out of an ambiguous
measurement.

**There is deliberately no built-in matcher.** See §5.1.

### 3.7 `risk.RiskEngine`

```
score = 100 × (1 − Π(1 − wᵢ))
```

See [RISK-SCORING.md](RISK-SCORING.md) for weights, bands and worked examples. The design
properties that matter — saturating, monotonic, order-independent — are asserted directly as
unit tests, because they are the contract, not an implementation detail.

### 3.8 `support` — key normalisation

`NameNormaliser` and `IdentityKeys` look like utilities and are actually a correctness
boundary.

**Order of operations is load-bearing:** apply ICAO multi-character transliterations
(`Ü`→`UE`, `ß`→`SS`) *first*, then strip remaining diacritics. Reversed, `MÜLLER` becomes
`MULLER`, which does not equal the `MUELLER` in the MRZ — and every German traveller
generates a false forgery finding. Under-normalising here produces a failure mode that is
both useless and discriminatory.

**Keys are refused rather than approximated.** An identity key requires a surname *and* a
date of birth; a document key requires at least four characters. A partial key would create
false matches, and in this system a false match means detaining the wrong traveller.

---

## 4. Data design

Detailed schema in [DATA-MODEL.md](DATA-MODEL.md). The design decisions:

**A document store, not a relational one.** A case is one deeply nested object written once
and read whole. Findings vary in shape per module — a copy-move finding carries a region and
a shift vector, a check-digit finding carries a field name — which columns model badly and a
document holds naturally. Nothing in the workload joins.

**Findings are denormalised into `risk.flags` as well as their module.** The console renders
a whole assessment without walking five modules. The duplication is acceptable because a
completed case is immutable.

**Evidence lives with the case in GridFS**, not on a filesystem or object store, so a
screening can be reconstructed and re-run from the database alone during an investigation.

**Two indexed lookup keys** (`documentNumberKey`, `identityKey`) exist solely to make
cross-case detection a query rather than a scan.

---

## 5. Key decisions and rejected alternatives

### 5.1 Face matching: a contract, not an implementation

**Rejected:** shipping a weak built-in matcher (histogram or template correlation) so
Module 4 always returns a number.

**Why rejected:** a similarity score that looks like a measurement but is not one is worse
than no score. It would feed the risk engine, appear in the audit trail, and be cited in a
decision about a person's liberty. The failure would be silent.

**Chosen:** `FaceVerifier` with an HTTP delegate; when unconfigured, Module 4 reports it did
not run. This also matches how biometrics are deployed in practice — an embedding model in
its own container, on its own hardware and retraining schedule.

### 5.2 The vision model reads; it does not judge

**Rejected:** asking the model "is this document genuine?", which it can answer plausibly.

**Why rejected:** that answer cannot be audited, reproduced, or appealed, and it collapses
goal 1. It also concentrates the whole decision in the one component with no error bound.

**Chosen:** the model is asked only to transcribe, and is explicitly instructed never to
correct, complete or normalise a value — a wrong-looking value is evidence and must survive
to validation unchanged. A model error then surfaces downstream as a failed check digit: a
visible, explainable finding rather than a silently wrong verdict.

### 5.3 Multiplicative risk rather than additive points

**Rejected:** summing severity points with a cap.

**Why rejected:** additive scoring lets a document be rejected by accumulated trivia — a
dozen "expiring soon" and "no camera info" notes outweighing nothing in particular — and
makes every threshold change reverberate unpredictably through unrelated cases.

**Chosen:** probabilistic combination, which saturates by construction.

### 5.4 Copy-move at native resolution

**Rejected:** analysing a downscaled image for speed (the obvious optimisation, and what the
first implementation did).

**Why rejected:** measured. A pasted region equals its source only in the original pixels;
smooth downscaling to a non-integer factor resamples the copy at a different subpixel phase
and the duplication becomes invisible. The detector found nothing on a document with an
obvious paste.

**Chosen:** native pixels with stride 1, whole-number decimation above 1200 px, and a light
blur before matching to absorb JPEG grid-phase differences. Cost is ~0.4 s, which the budget
absorbs.

### 5.5 Oversubscribed signature buckets are dropped, not truncated

**Rejected:** capping bucket size to bound the pair enumeration.

**Why rejected:** measured. Truncation keeps whichever blocks were scanned first and
silently discards the duplicate being searched for, because a pasted region is scanned last.
The bug was invisible — the detector simply never fired.

**Chosen:** a signature shared by more than a handful of blocks is repeated printing and is
discarded entirely.

### 5.6 Every well-supported shift is examined

**Rejected:** taking only the highest-voted displacement vector.

**Why rejected:** on a real document the top vector is the guilloche pitch, because security
printing repeats across the whole page while a forged stamp covers a small part of it.
Judging only the top shift lets heavy security printing mask an actual forgery — precisely
on the documents this must work for.

### 5.7 Watchlist entries are deactivated, never deleted

A watchlist is evidence. Deleting a row erases the reason a past case was rejected.

### 5.8 Modules run sequentially

**Deferred, not rejected.** Modules 2–4 are independent given Module 1's output and could
run concurrently, cutting the ~0.5 s figure substantially. The risk engine is
order-independent by construction specifically so this remains a safe change. It is not done
yet because there is no load to justify the added failure modes.

---

## 6. Error handling strategy

Three layers, each with a different rule:

| Layer | Rule |
|---|---|
| Detector / engine | Catch and return empty or throw to the module. Never propagate to the pipeline |
| Module | Never throws. Returns `FAILED` with a reason, or `SKIPPED` if inapplicable |
| Pipeline | Outermost `catch`; marks the case `FAILED` and persists it anyway |
| API | `GlobalExceptionHandler` maps to 400 / 413 / 500 with the server's own message |

**Errors are surfaced with their real cause, not a generic message.** Whether a screening
failed because the image exceeded the size limit or because no OCR engine is installed
demands different responses at the desk.

The risk engine's coverage rule (§3.2) is what stops this leniency becoming dangerous: a
case that could not be fully examined is never returned as `CLEAR`.

---

## 7. Interface design

REST reference in [API.md](API.md). Design notes:

- **One synchronous endpoint** returns the complete case. Screening takes under a second, so
  a job-and-poll API would add latency and complexity for nothing. If face matching or chip
  authentication later pushes this past a few seconds, the case is already a persisted
  entity with a reference, so an async variant is additive.
- **`documentType` is a hint, never a constraint.** Modules report what they find. A
  mis-selected type must not change what is detected.
- **Optional `text` part** carries a chip read or keyed MRZ and is trusted over pixel OCR,
  because when the text is known, OCR can only introduce error.
- **Summary and detail are separate representations.** The case list would be unusable if
  each row carried five module results and their evidence maps.

### Console design

- The rationale and each finding's evidence render inline, never behind a click, so an
  officer can challenge a recommendation rather than only obey it.
- "Did the module run" and "did it find anything" are shown as separate statements. A
  completed module that raised three findings must never render as reassuring — an earlier
  version coloured it green, which was the most dangerous defect found in the UI.

---

## 8. Performance design

Measured ~400–700 ms per document; Module 3 is ~85% of it.

| Stage | Typical | Bound by |
|---|---|---|
| Module 1 (supplied text) | <5 ms | Parsing |
| Module 2 | 2–3 ms | Arithmetic |
| Module 3 | 350–600 ms | Copy-move at native resolution |
| Module 4 | – | Network, when configured |
| Watchlist | 20–40 ms | Two indexed queries |

Cost controls: analysis-resolution caps per detector, whole-number decimation, block-size
strides, per-shift match caps, and an examined-shift limit. Each is a deliberate accuracy/
cost trade documented at its constant.

**Statistics report a median, not a mean**, so one pathological image cannot distort the
number a supervisor uses to judge whether lanes are keeping up.

---

## 9. Security design

The system is itself a security control with motivated adversaries. Current state is
explicit: **it is not deployable as it stands.**

| Concern | State |
|---|---|
| Authentication | **Absent.** `officerId` is free text — unacceptable for an audit trail that may be used in a prosecution |
| Transport | **Absent.** TLS required before any deployment; evidence images must never traverse plaintext |
| Upload hardening | Size caps only. Needs rate limiting and content validation |
| Evidence access logging | Reading a stored image is not currently audited |
| Retention | Nothing expires. A legal question to settle per jurisdiction, then enforce |
| Injection into the vision prompt | The model receives only the image and a fixed instruction; extracted text is never executed or interpreted as instruction |
| Audit integrity | Append-only by convention, not enforced. Write-once storage would strengthen it |

These are tracked as blocking items in [TASKS.md](TASKS.md).

---

## 10. Testing design

See [TESTING.md](TESTING.md). The principle: **each test targets a property the design
depends on**, not line coverage.

- The MRZ parser is tested against published ICAO specimens, so a fixture cannot silently
  agree with a broken implementation.
- Risk-engine properties (saturation, monotonicity, order-independence, the coverage rule)
  are asserted directly.
- The copy-move pair — silent on security printing, catches a real paste and localises it —
  is the regression guard for every threshold in that detector.
- The pipeline is exercised end-to-end against an embedded MongoDB, so integration tests
  need no Docker daemon.

---

## 11. Deployment design

See [DEPLOYMENT.md](DEPLOYMENT.md). Three profiles by intent:

| Profile | Database | Use |
|---|---|---|
| `embedded-mongo` | In-process, discarded on exit | Local development with no infrastructure |
| default | External MongoDB | Shared/staging |
| production | Replica set, TLS, auth | Not yet configured — see §9 |

The optional capabilities are the deployment variable: a checkpoint with no external network
runs Tesseract offline; one with connectivity adds vision OCR; one with biometric hardware
adds Module 4. No code differs between them.
