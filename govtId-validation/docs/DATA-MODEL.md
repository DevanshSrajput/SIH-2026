# Data Model

MongoDB, four collections plus GridFS. Indexes are created automatically
(`spring.data.mongodb.auto-index-creation: true`).

## Why a document store

A screening case is one deeply nested object written once and read whole: extracted fields,
five module results, each with a variable-length list of findings, each finding carrying an
arbitrary evidence map. Findings differ in shape by module — a copy-move finding carries a
region and a shift vector, a check-digit finding carries a field name — which relational
columns model badly and which a document naturally holds. Nothing in the workload joins.

---

## `screening_cases`

One document presented at a checkpoint and everything concluded about it.

| Field | Type | Notes |
|---|---|---|
| `_id` | ObjectId | |
| `caseReference` | String | **unique index**. `BRD-XXXXXX`, from an alphabet excluding I, O, 0 and 1 so it is unambiguous read aloud over a radio |
| `checkpointId`, `laneId`, `officerId` | String | Where and by whom |
| `documentType` | String | `PASSPORT`, `VISA`, … |
| `status` | String | `RECEIVED`, `PROCESSING`, `COMPLETED`, `FAILED` |
| `documentImageId` | String | GridFS id of the document image |
| `liveCaptureImageId` | String | GridFS id of the live capture |
| `extracted` | Object | See below |
| `moduleResults` | Array | One entry per module |
| `risk` | Object | Score, band, verdict, flags, rationale |
| `officerDecision` | String | The officer's own determination; never overwrites `risk.verdict` |
| `officerNotes`, `decidedAt` | String, Date | |
| `documentNumberKey` | String | **index**. Normalised document number |
| `identityKey` | String | **index**. `SURNAME|GIVENNAMES|YYYY-MM-DD` |
| `createdAt`, `completedAt` | Date | |
| `processingMillis` | Long | |

### `extracted`

Identity fields (`surname`, `givenNames`, `documentNumber`, `issuingState`, `nationality`,
`dateOfBirth`, `dateOfIssue`, `dateOfExpiry`, `sex`, `personalNumber`), visa fields
(`visaNumber`, `visaType`, `entryType`, `stayDurationDays`, `validFrom`, `validUntil`), and
provenance (`mrz`, `rawText`, `ocrConfidence`, `engine`).

Plus a parallel set of **printed-page** readings — `printedSurname`, `printedGivenNames`,
`printedDocumentNumber`, `printedDateOfBirth`, `printedDateOfExpiry` — captured
independently of the MRZ. Storing the same field twice is deliberate: a genuine document
states its identity twice, and disagreement between the two readings is direct evidence of
text manipulation. Collapsing them into one field would destroy the check.

Every field is nullable. A damaged document legitimately yields gaps, and Module 2 treats a
gap as a finding rather than an error.

### `mrz`

```json
{
  "format": "TD3",
  "lines": ["P<SWEERIKSSON<<ANNA<MARIA<<<...", "L898902C36SWE7408122F3004157<<<...04"],
  "checkDigits": { "documentNumber": true, "dateOfBirth": false, "dateOfExpiry": true, "personalNumber": true },
  "composite": true
}
```

The raw lines are retained: they are the primary evidence for any check-digit finding, and
an investigator must be able to recompute the arithmetic. `composite` is `null` for visa
MRZs, which carry no composite digit — distinct from `false`, which means it failed.

### `moduleResults[]`

```json
{
  "module": "TAMPERING_DETECTION",
  "status": "COMPLETED",
  "durationMillis": 447,
  "flags": [ { "code": "...", "module": "...", "severity": "...", "message": "...", "evidence": { } } ],
  "details": { "detectorsRun": [...], "corroboratingDetectors": [...] },
  "note": "2 indicator(s)"
}
```

`details` holds what the module observed even when it raised nothing — which detectors ran,
which stayed silent, what the medians were. This is what lets an officer see *why* nothing
fired, not just that nothing did.

### `risk`

```json
{ "score": 75, "band": "CRITICAL", "verdict": "REJECT", "flags": [...], "topReasons": [...], "explanation": "..." }
```

Findings are stored again here, flattened and ordered most severe first, so the console can
render the whole assessment without walking every module.

---

## `watchlist`

| Field | Type | Notes |
|---|---|---|
| `_id` | ObjectId | |
| `documentNumberKey` | String | **index**. Normalised; nullable for name-only entries |
| `identityKey` | String | **index**. Nullable for document-only entries |
| `displayName`, `nationality`, `dateOfBirth` | | For officer display |
| `listType` | String | `STOLEN_DOCUMENT`, `REVOKED_DOCUMENT`, `ENTRY_BAN`, `WANTED`, `VISA_OVERSTAY`, `LOCAL_INTEREST` |
| `severity` | String | Default `CRITICAL` |
| `reason`, `source` | String | Provenance of the listing |
| `active` | Boolean | **Entries are deactivated, never deleted** |
| `addedAt`, `addedBy` | Date, String | |

Deactivation rather than deletion is a deliberate constraint: a watchlist is evidence, and
removing a row would erase the reason any past case was rejected.

---

## `audit_events`

Append-only. Nothing updates or deletes a row.

| Field | Type | Notes |
|---|---|---|
| `_id` | ObjectId | |
| `caseId` | String | **index**. Null for watchlist events |
| `actor` | String | Officer id |
| `action` | String | `SCREENED`, `DECISION_RECORDED`, `WATCHLIST_ENTRY_ADDED`, `WATCHLIST_ENTRY_DEACTIVATED` |
| `detail` | String | Human-readable |
| `data` | Object | Structured context — score, verdict, flag count, list type |
| `occurredAt` | Date | **index** |

A `DECISION_RECORDED` event stores both the officer's decision and the system
recommendation it was taken against, so a divergence is visible without re-reading the case.

---

## GridFS (`fs.files`, `fs.chunks`)

Document images and live captures. Written **before** analysis begins, so a crash mid-run
still leaves the evidence recoverable against the case reference.

---

## Key normalisation

Both lookup keys are built by `IdentityKeys` and normalised by `NameNormaliser`:

1. Upper-case.
2. Apply ICAO multi-character transliterations (`Ü`→`UE`, `ß`→`SS`, `Å`→`AA`, `Ø`→`OE`, …).
3. Strip remaining diacritics.
4. Remove everything that is not `A–Z` or `0–9`.

Order matters. Stripping the diacritic first would turn `MÜLLER` into `MULLER`, which does
not equal the `MUELLER` in the MRZ, and every German traveller would generate a false
mismatch.

Keys are only produced when there is enough data to be meaningful — an identity key needs
both a surname and a date of birth, and a document key needs at least four characters. A
partial key would create false matches, which here means detaining the wrong traveller.

---

## Retention

Not yet implemented. Retention periods for case history and evidence images are a legal
question that must be settled per jurisdiction before deployment — see
[TASKS.md](TASKS.md).
