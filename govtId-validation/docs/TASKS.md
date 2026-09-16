# Task Breakdown

## Delivered

### Foundation
- [x] Maven-wrapper Spring Boot 4.1 project on Java 21 (no system Maven required)
- [x] MongoDB via Docker Compose, plus an `-Pembedded-mongo` profile for machines with
      neither a Docker daemon nor a local `mongod`
- [x] Domain model: `ScreeningCase`, `ExtractedFields`, `MrzData`, `ModuleResult`,
      `RiskFlag`, `RiskAssessment`, `WatchlistEntry`, `AuditEvent`
- [x] GridFS evidence storage, written before analysis

### Module 1 — OCR Extraction
- [x] `OcrEngine` contract with priority-ordered, self-declaring backends
- [x] ICAO 9303 MRZ parser: TD1, TD2, TD3, MRV-A, MRV-B, with check-digit verification
- [x] Printed-label field extraction, including visa-only terms
- [x] Independent capture of printed values for the data-page cross-check
- [x] Supplied-text backend (chip read / keyed MRZ)
- [x] Tesseract backend, active when the binary is present
- [x] Claude vision backend, active when a credential is present

### Module 2 — Document Validation
- [x] MRZ check-digit and composite verification
- [x] Data page vs MRZ cross-check with ICAO transliteration
- [x] Chronology rules: expiry, expiring soon, future birth, implausible age,
      expiry-before-birth, issue-after-expiry, over-long validity
- [x] ISO 3166-1 alpha-3 and ICAO special code validation
- [x] Passport number format, mandatory-field presence
- [x] Visa term rules: window, expiry, not-yet-valid, stay-exceeds-validity, entry type

### Module 3 — Tampering Detection
- [x] `TamperingDetector` contract
- [x] Error Level Analysis with clustering test and officer-facing heat map
- [x] Noise consistency with anomalous-fraction guard
- [x] Copy-move detection surviving guilloche security printing
- [x] EXIF/XMP metadata forensics with asymmetric weighting
- [x] Per-technique corroboration

### Module 4 — Face Verification
- [x] `FaceVerifier` contract with two-threshold decision
- [x] HTTP delegate to an external biometric service
- [x] Honest unavailability: no similarity is estimated when no matcher is configured

### Watchlist and identity screening
- [x] Blacklist matching on document number and identity
- [x] One-document-many-identities detection
- [x] One-identity-many-documents detection
- [x] Presentation velocity
- [x] Deactivate-not-delete watchlist maintenance

### Risk and API
- [x] Multiplicative risk engine with coverage rule and written rationale
- [x] REST API: screening, cases, evidence images, audit trail, officer decision,
      watchlist CRUD, checkpoint statistics
- [x] Append-only audit trail

### Officer console
- [x] Screening page with document, live capture and chip-read input
- [x] Case detail: verdict, extracted identity, module status, findings with evidence,
      evidence images, ELA heat map, audit trail, officer decision
- [x] Case list, watchlist management, checkpoint dashboard

### Tests — 59 passing
- [x] MRZ parser against the published ICAO specimens, including tamper cases
- [x] Validation rules against a fixed clock
- [x] Risk engine: saturation, monotonicity, order-independence, coverage rule
- [x] Copy-move: silent on security printing, catches a real paste, localises it
- [x] Corroboration counted per technique, not per finding
- [x] Identity key normalisation and ICAO transliteration
- [x] Full pipeline end-to-end against an embedded MongoDB

---

## Before any deployment

These are blocking. The system is functionally complete but not deployable as it stands.

- [ ] **Authentication and authorisation.** The API is unauthenticated. Officers need
      identities, roles (screen / review / administer watchlist) and session handling.
      `officerId` is currently a free-text field, which is unacceptable for an audit trail
      that may be used in a prosecution.
- [ ] **Transport security.** TLS everywhere; evidence images must never traverse plaintext.
- [ ] **Data retention policy.** Retention periods for case history and evidence images are
      a legal question that must be settled per jurisdiction, then enforced by a scheduled
      purge. Currently nothing expires.
- [ ] **Access logging on evidence.** Reading a stored document image is itself an event
      worth recording; only screenings and decisions are audited today.
- [ ] **Rate limiting and upload hardening** on the multipart endpoint.

## Next capabilities

- [ ] **Face matching service.** Deploy an embedding model behind the existing
      `POST /compare` contract. No backend code changes needed.
- [ ] **Chip authentication.** Passive and active authentication against the CSCA
      certificate chain would verify an e-passport cryptographically — a categorically
      stronger check than anything in Module 2, and the natural next module.
- [ ] **INTERPOL SLTD integration** for stolen and lost travel documents, replacing the
      locally maintained watchlist as the primary source.
- [ ] **Per-issuer document templates.** Expected field layout, fonts and security features
      per issuing state, enabling layout-conformance checks.
- [ ] **Liveness detection** on the live capture, to defeat a photograph held to the camera.

## Quality and operations

- [ ] **Detector calibration against a labelled corpus.** Thresholds are currently reasoned
      from first principles and validated on synthetic documents. Real precision and recall
      figures require a corpus of genuine and known-forged documents, and no threshold
      should be presented as tuned until then.
- [ ] **Per-detector metrics** — firing rate and processing time by detector, so a
      misbehaving technique is visible before it floods officers with false positives.
- [ ] **Parallel module execution.** The four modules are independent given Module 1's
      output; running Modules 2–4 concurrently would cut the ~0.5 s figure substantially.
      Deliberately deferred: the risk engine is order-independent by construction, so this
      is a safe change to make once there is load to justify it.
- [ ] **Officer feedback loop.** Where an officer's determination diverges from the
      recommendation, that divergence is the most valuable calibration signal available. It
      is recorded but not yet analysed.
- [ ] **Frontend tests.** The console has none.
- [ ] **Load testing** at realistic checkpoint volumes.

## Known limitations

Carried from the README, restated here as work items:

- [ ] Copy-move detection weakens on heavily re-compressed images and cannot see rotated or
      rescaled pastes.
- [ ] Error Level Analysis is inapplicable to losslessly stored documents.
- [ ] Cross-case detection is blind on a first presentation.
- [ ] Metadata findings are trivially defeated by stripping metadata, and are weighted low
      accordingly.
