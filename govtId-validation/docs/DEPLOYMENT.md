# Deployment and Operations

> **The system is not deployable as it stands.** It has no authentication and no transport
> security. Section [Before production](#before-production) lists what is blocking. Nothing
> below should be read as clearance to run this against real travellers.

---

## Profiles

| Profile | Database | Intended use |
|---|---|---|
| `embedded-mongo` | In-process, discarded on exit | Local development with no infrastructure |
| default | External MongoDB | Shared or staging environments |
| production | Replica set, TLS, authentication | Not yet configured |

### Local development

No Docker daemon and no local `mongod` required:

```bash
cd backend && ./mvnw spring-boot:run -Pembedded-mongo
```

```bash
cd frontend && npm run dev
```

The console proxies `/api` to the backend, so the browser sees one origin.

### With a real MongoDB

```bash
docker compose up -d mongo
```

```bash
cd backend && ./mvnw spring-boot:run
```

Optional database browser (not for any shared environment — it is unauthenticated):

```bash
docker compose --profile tools up -d mongo-express
```

### Building artefacts

```bash
cd backend && ./mvnw clean package
```

Produces `backend/target/screening-0.1.0.jar`, runnable with `java -jar`.

```bash
cd frontend && npm run build
```

Produces `frontend/dist/`, static files for any web server. Point it at the API by serving
it behind a reverse proxy that maps `/api` to the backend.

---

## Configuration

All settings are environment-overridable. Full table in [API.md](API.md#configuration).

The ones that change behaviour rather than plumbing:

| Variable | Effect of changing it |
|---|---|
| `MONGODB_URI` | Where case history and evidence live |
| `CORS_ORIGINS` | Which console origins may call the API |
| `screening.risk.reject-threshold` | Raising it means fewer rejections and more missed forgeries |
| `screening.risk.review-threshold` | Lowering it increases officer workload; too low and referrals stop being read |
| `FACE_SERVICE_URL` | Empty disables Module 4 entirely |
| `screening.face.mismatch-threshold` | The point below which a traveller is treated as an impostor |
| `screening.watchlist.velocity-threshold` | Presentations per 24 h before reuse is flagged |

**Threshold changes are operational decisions with consequences for travellers, not tuning
knobs.** Change them deliberately, record why, and re-run the suite.

---

## Capability matrix

The same build behaves differently depending on what is available to it. This is the
intended deployment variable — no code differs between these.

| Checkpoint situation | Module 1 reads via | Module 4 |
|---|---|---|
| No external network, no extra software | Supplied MRZ only (chip reader or officer keying) | Not run |
| Tesseract installed | Tesseract, offline | Not run |
| Anthropic credential present | Claude vision | Not run |
| Biometric service reachable | Any of the above | Active |

Modules 2 and 3 are fully self-contained and always run.

To activate:

- **Tesseract** — install and put it on `PATH`, or set `TESSERACT_BINARY`.
- **Vision OCR** — provide an Anthropic credential in the environment. The engine
  deactivates itself and logs once if absent.
- **Face matching** — set `FACE_SERVICE_URL` to a service exposing `POST /compare`
  (contract in [API.md](API.md#face-service-contract)).

---

## Health and monitoring

| Endpoint | Purpose |
|---|---|
| `GET /actuator/health` | Liveness and readiness, including MongoDB |
| `GET /actuator/info` | Build information |
| `GET /actuator/metrics` | JVM and HTTP metrics |
| `GET /api/stats?windowHours=8` | Operational view: throughput, referral rate, median screening time, most frequent findings |

### What to watch

| Signal | Why it matters |
|---|---|
| **Referral rate** | The health metric for the whole system. A rate climbing past what officers can absorb means findings stop being read, and the platform becomes worse than nothing |
| **Median screening time** | Lane throughput. Reported as a median so one pathological image cannot hide a real regression |
| **Spike in one finding code** | Often the first sign of a batch of forgeries circulating — or of a detector misbehaving after a change. Both are worth an alert |
| **Module `FAILED` counts** | A capability has broken. Cases are being referred on incomplete evidence |
| **Engine in use** | If vision or Tesseract silently deactivates, Module 1 falls back and detection quality drops without an error |

Per-detector firing rates and timings are not yet exported — see [TASKS.md](TASKS.md).

---

## Operational notes

**Startup logs state which optional capabilities are active.** Each engine logs once at
startup whether it is available. Check these first when detection quality changes
unexpectedly; a missing credential is silent by design.

**Screening is stateless per request.** The API scales horizontally behind a load balancer;
all shared state is in MongoDB.

**Evidence images dominate storage.** Roughly the size of each uploaded image per case, in
GridFS. Capacity planning follows passenger volume directly, and there is currently no
expiry — see below.

**A screening that fails is still persisted.** A case with status `FAILED` is not an error to
be cleaned up; it is a record an investigator may need.

---

## Before production

Blocking. Tracked in [TASKS.md](TASKS.md), restated here because they are deployment gates.

1. **Authentication and authorisation.** `officerId` is currently free text. An audit trail
   that may be used in a prosecution cannot rest on an unverified string. Officers need
   real identities and roles (screen / review / administer watchlist).
2. **TLS everywhere.** Evidence images and identity data must never traverse plaintext.
3. **Data retention policy.** Nothing expires today. Retention for case history and evidence
   images is a legal question to settle per jurisdiction, then enforce with a scheduled
   purge.
4. **Evidence access logging.** Reading a stored document image is an event worth recording;
   only screenings and decisions are audited now.
5. **Rate limiting and upload hardening** on the multipart endpoint.
6. **MongoDB authentication, TLS and a replica set.** The bundled Compose file runs an
   unauthenticated single node suitable only for development.
7. **Detector calibration against a labelled corpus.** Until precision and recall are
   measured on genuine and known-forged documents, no accuracy claim should be made to
   officers relying on the output.

---

## Backup and recovery

Not yet specified. Case history and evidence are the system's entire value and its
investigative record; a backup and restore procedure with a tested recovery drill is
required before production and is not covered by anything in this repository today.
