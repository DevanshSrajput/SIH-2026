# Next Steps

Remaining work to take the project from prototype to production-ready.

---

## Priority 1 — Critical (must complete for demo)

### Face Verification Accuracy
- [x] Verify the `raw_detection` fix produces correct similarity scores after restart
- [x] Fix the score rescaling that was the false-accept source — see *Known issues fixed* below
- [x] Fix YuNet's row layout (score is the **last** element, not index 4)
- [x] Add a three-way decision: MATCH / NO_MATCH / **UNCERTAIN**, with reasons
- [x] Add a face quality gate — blur, exposure, contrast, resolution, pose, detector confidence
- [x] Add `calibrate.py` to measure the real score distributions and the false accept rate
- [x] Add a face enrollment endpoint — `POST /api/face/enrolments`
- [x] Persist face embeddings in MongoDB for repeat visitors — `FaceEnrolment`
- [ ] **Test with real passport photos + live captures of different people** — the one
      thing that cannot be done without real images. Run `python calibrate.py samples/`
      and raise `MATCH_THRESHOLD` until the false accept rate is zero.
- [ ] If SFace fast model is not discriminative enough, switch to SFace accurate (~94MB)

### End-to-End Validation
- [x] Run a complete screening flow: upload passport → OCR → validation → tampering → face → verdict
- [x] Verify all risk flags appear correctly in the frontend
- [ ] Test with forged documents (altered MRZ, pasted photo, copy-move) — needs sample forgeries
- [ ] Test with genuine documents to confirm low false-positive rate — needs real documents

---

## Priority 2 — Important (should complete before submission)

### Backend
- [x] Auto-detect the Tesseract path — the configured value first, then the standard
      install locations per platform. No `PATH` manipulation needed.
- [x] Add structured error responses — every failure returns an `ApiError`, never a stack trace
- [x] Add request validation — uploads are size-checked and magic-byte sniffed, so a PDF
      renamed to `.jpg` is rejected at the door with a message that says so
- [x] Add API rate limiting — per-client fixed window on writes only
- [x] Fix the `PageImpl` serialization warning — `PagedModel` on every paged endpoint
- [ ] Configure an `ANTHROPIC_API_KEY` to activate Claude Vision OCR — needs a credential

### Frontend
- [x] Wire dashboard charts to real API statistics — volume trend, severity distribution,
      officer agreement rate, all from `GET /api/stats`
- [x] Build watchlist management UI — add, **edit**, deactivate, **reactivate**, **import/export**
- [x] Improve case detail page — expandable per-module breakdown with every finding's evidence
- [x] Add loading states and error boundaries for all API calls
- [x] Show real-time face quality feedback during live capture
- [x] Document type selector (was already present on the screening page)

### Face Verification
- [x] Add face quality assessment — rejects blurry, dark, small, or badly angled captures
- [x] Support 1:1 (verify) and 1:N (identify against the enrolled gallery)
- [ ] Improve liveness detection — depth estimation or IR check. Needs hardware that
      reports depth; the current heuristics catch prints and screens, not 3D masks.

---

## Priority 3 — Nice to have (post-demo)

### Infrastructure
- [x] Create `docker-compose.yml` for all four services (Mongo, face, backend, console)
- [x] Dockerfiles for the backend (multi-stage, non-root, Tesseract included) and the
      console (nginx, proxying `/api` and `/face-service` so there is one origin)
- [x] Package face models inside the Docker image — fetched at build time, never at runtime
- [ ] Set up persistent MongoDB for production data — compose has a volume; Atlas is a decision
- [ ] Add centralized logging (ELK stack or similar)
- [ ] Add health check dashboard for monitoring all services
- [ ] Set up CI/CD pipeline (GitHub Actions)

### Offline / Edge Deployment
- [x] The face service runs standalone with a file-backed enrolment gallery, for an edge
      node with no database
- [ ] Add offline mode — queue screenings when the backend is unreachable, sync later
- [ ] Optimize for low-bandwidth environments (compress images before upload)

### Security
- [x] Add rate limiting
- [ ] Add JWT authentication for API access
- [ ] Add role-based access control (officer, supervisor, admin)
- [ ] Encrypt sensitive data at rest (passport numbers, face embeddings)
- [x] Audit logging for screening actions, watchlist changes and face enrolments
- [ ] Add CSRF protection

### User Experience
- [ ] Responsive design for tablets and handheld devices
- [ ] Keyboard shortcuts for common actions (upload, approve, reject)
- [ ] Multi-language support (Hindi, English, regional languages)
- [ ] Print-friendly screening report format
- [ ] Dark mode — the console is already dark-first; a light mode is the missing half

---

## Priority 4 — Long term

### AI / ML Improvements
- [ ] Train a custom face recognition model on border checkpoint data
- [ ] Add document authenticity scoring using a trained classifier
- [ ] Implement OCR confidence scoring — flag low-confidence extractions
- [ ] Add MRZ generation — allow officers to create test documents for training

### Integration
- [ ] INTERPOL SLTD (Stolen and Lost Travel Documents) API integration
- [ ] National identity database lookup (Aadhaar, passport databases)
- [ ] Visa verification API (embassy/consulate systems)
- [ ] Biometric device integration (fingerprint scanners, iris cameras)

*All four need credentials and access agreements that do not exist yet. The watchlist
import endpoint is the integration point: a feed from any of these lands as a bulk
import.*

### Analytics
- [x] Screening volume analytics (`series` in `GET /api/stats`, charted on the dashboard)
- [x] Flag pattern analysis — most frequent finding codes, and a severity breakdown
- [x] Officer agreement rate — how often officers override the recommendation, which is a
      signal about the thresholds rather than about the officers
- [ ] Risk trend analysis over longer horizons
- [ ] Officer performance metrics (screenings per hour, accuracy rates)

### Documentation
- [x] Face service README rewritten around the decision rule and the threshold scale
- [ ] Write a non-technical user manual for border officers
- [ ] Update the Postman collection with the enrolment and identification endpoints
- [ ] Record a demo video walkthrough
- [ ] Write deployment guide for production environments

---

## Known issues fixed

Three defects in the face pipeline, each of which was a false-accept source.

### 1. Similarity was rescaled, destroying the separation between populations

`(cosine + 1) / 2` was applied in both the Python service and `LocalFaceVerifier`. It
looks harmless and is not: it compresses SFace's whole useful range into roughly
[0.45, 0.90], so two strangers scoring a genuine 0.10 were reported as "0.55 similar" —
landing inside the old 0.55–0.75 review band, and within reach of a match.

Fixed by using the raw cosine throughout, with thresholds recalibrated onto that scale
(0.46 / 0.28, against SFace's published 0.363 break-even).

### 2. YuNet's score and landmarks were read from the wrong indices

The row layout is `[x, y, w, h, 10 landmark coords, score]` — the score is the **last**
element. The code read index 4 as the confidence, which is the right eye's
x-coordinate, and took landmarks from indices 5–14, shifting every one of them by a
half-coordinate.

Two consequences. Detector confidence came back as values like 233 instead of 0.71, so
`setScoreThreshold` was comparing against nonsense and filtering nothing. More seriously,
the shifted landmarks fed `alignCrop` a face warped to the wrong canonical position,
which degrades every embedding and pulls unrelated faces closer together — a
false-accept source hiding in an array index, visible only as slightly worse scores.

Pinned by `TestYuNetRowLayout` in `tests/test_decision.py`.

### 3. `LocalFaceVerifier` subtracted the wrong mean, and could never align

It passed the *face detector's* Caffe mean (104, 177, 123) to SFace, which takes raw BGR
and wants no mean subtraction — shifting every embedding by a constant. And its Caffe SSD
detector reports no landmarks at all, so alignment was impossible.

Fixed the mean. The alignment limitation cannot be fixed without a landmark detector, so
the verifier now reports `alignmentUsed: false` and `FaceVerificationService` refuses to
turn its scores into a positive identification. A degraded matcher that says "I cannot
confirm this" is useful; one that guesses is not.

---

## Completed

- [x] Project architecture and documentation (11 docs)
- [x] Java backend with all four screening modules
- [x] React frontend with officer console
- [x] Python face verification service (YuNet + SFace)
- [x] Embedded MongoDB (no Docker required)
- [x] Tesseract OCR integration
- [x] 92 backend tests passing
- [x] 58 face service tests passing
- [x] Frontend builds clean (0 lint warnings)
- [x] Face detector rewritten from insightface to OpenCV FaceDetectorYN
- [x] Face recognizer rewritten from insightface to OpenCV FaceRecognizerSF
- [x] alignCrop landmark fix for proper face alignment
- [x] README.md and .gitignore updated
- [x] start.ps1 fixed for current setup
