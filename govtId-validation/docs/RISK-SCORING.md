# Risk Scoring

## The model

Findings are combined the way independent evidence combines, not by adding points:

```
score = 100 × (1 − Π(1 − wᵢ))
```

where `wᵢ` is the severity weight of finding *i* as a fraction. Each finding reduces the
remaining probability that the document is sound.

| Severity | Weight | Meaning |
|---|---|---|
| `CRITICAL` | 70 | On its own, decisive |
| `HIGH` | 32 | Strong evidence of forgery or fraud |
| `MEDIUM` | 15 | Needs explanation |
| `LOW` | 6 | Worth recording; not on its own actionable |
| `INFO` | 0 | Context only; never moves the score |

### Why multiplicative

Three properties follow, and all three matter at a checkpoint:

- **It saturates.** A pile of minor observations can never out-score one decisive finding.
  Twelve `LOW` findings reach 53; a single `CRITICAL` reaches 70. A document cannot be
  rejected by accumulated trivia.
- **It is monotonic.** More evidence never lowers the score, so a finding can never be
  cancelled out by an unrelated one.
- **It is order-independent.** Modules can run in any order, or in parallel, without moving
  the verdict.

## Bands and verdicts

| Score | Band | Verdict | Meaning |
|---|---|---|---|
| 0–19 | LOW | CLEAR | No obstacle to entry on document grounds |
| 20–34 | MEDIUM | CLEAR | Recorded, below the referral threshold |
| 35–44 | MEDIUM | REVIEW | Refer to an officer before deciding |
| 45–69 | HIGH | REVIEW | Refer to an officer before deciding |
| 70–100 | CRITICAL | REJECT | Do not accept without supervisory review |

Thresholds are configurable (`screening.risk.reject-threshold`,
`screening.risk.review-threshold`).

### The coverage rule

A module that **failed** is missing evidence, not absent evidence. A case with a failed
module is never returned as `CLEAR`; it is downgraded to `REVIEW`, and the rationale says
the assessment rests on partial evidence.

A **skipped** module does not trigger this. Skipping is an expected configuration —
Module 4 is skipped whenever no live capture was taken — whereas failure means something
that should have worked did not.

## The rationale

Every assessment carries a written explanation naming the finding count, the severity mix,
the single largest contributor, any incomplete module, and the recommended action. It is
shown on the console rather than hidden behind a click, so the recommendation can be
challenged rather than merely obeyed.

## Officer override

The officer's determination is recorded alongside the system's, never over it. Both are
kept, because a divergence between them is exactly what a later review needs to see.

---

# Finding catalogue

## Module 1 — OCR Extraction

| Code | Severity | Raised when |
|---|---|---|
| `MRZ_NOT_FOUND` | HIGH / LOW | No MRZ could be read. HIGH for a document type required to carry one (passport, visa, national ID), LOW otherwise |
| `OCR_LOW_CONFIDENCE` | MEDIUM | Text recovery confidence below 60% |

## Module 2 — Document Validation

| Code | Severity | Raised when |
|---|---|---|
| `MRZ_CHECKDIGIT_MISMATCH` | HIGH | A printed MRZ check digit does not match the recomputed value |
| `MRZ_COMPOSITE_MISMATCH` | HIGH | The composite check digit does not match |
| `DATAPAGE_MRZ_DOB_MISMATCH` | CRITICAL | Printed date of birth disagrees with the MRZ |
| `DATAPAGE_MRZ_DOCNUMBER_MISMATCH` | CRITICAL | Printed document number disagrees with the MRZ |
| `DATAPAGE_MRZ_EXPIRY_MISMATCH` | CRITICAL | Printed expiry disagrees with the MRZ |
| `DATAPAGE_MRZ_SURNAME_MISMATCH` | HIGH | Printed surname disagrees with the MRZ (after transliteration) |
| `DATAPAGE_MRZ_GIVENNAMES_MISMATCH` | MEDIUM | Printed given names disagree with the MRZ |
| `DOB_IN_FUTURE` | CRITICAL | Date of birth is in the future |
| `EXPIRY_BEFORE_BIRTH` | CRITICAL | Expiry precedes date of birth |
| `DOB_IMPLAUSIBLE` | HIGH | Implied age over 120 |
| `DOCUMENT_EXPIRED` | HIGH | Past its expiry date |
| `ISSUE_AFTER_EXPIRY` | HIGH | Issue date later than expiry |
| `UNKNOWN_ISSUING_STATE` | HIGH | Issuing state is not a recognised country or ICAO code |
| `UNKNOWN_NATIONALITY` | HIGH | Nationality is not a recognised country or ICAO code |
| `VALIDITY_PERIOD_EXCEEDS_STANDARD` | MEDIUM | Validity longer than the 10-year passport maximum |
| `DOCUMENT_NUMBER_FORMAT` | MEDIUM | Passport number breaks the ICAO 9-character alphanumeric format |
| `MANDATORY_FIELD_MISSING` | MEDIUM | A mandatory field could not be established |
| `DOCUMENT_EXPIRING_SOON` | LOW | Under 180 days of validity remaining |
| `INVALID_SEX_CODE` | LOW | Sex is not `M`, `F` or `X` |
| `VISA_VALID_FROM_AFTER_UNTIL` | HIGH | Visa validity window starts after it ends |
| `VISA_EXPIRED` | HIGH | Visa validity has passed |
| `VISA_NOT_YET_VALID` | MEDIUM | Visa validity has not started |
| `VISA_STAY_EXCEEDS_VALIDITY` | MEDIUM | Permitted stay longer than the validity window |
| `VISA_UNKNOWN_ENTRY_TYPE` | LOW | Entry type not resolvable to SINGLE / DOUBLE / MULTIPLE |

## Module 3 — Tampering Detection

| Code | Severity | Raised when |
|---|---|---|
| `ELA_SPLICE_SUSPECTED` | HIGH / MEDIUM | A localised region compresses differently from its surroundings. HIGH when the region exceeds 2% of the image |
| `COPY_MOVE_DUPLICATION` | HIGH / MEDIUM | A compact region appears twice at one offset. HIGH for a large region |
| `META_EDITING_SOFTWARE` | HIGH | EXIF `Software` names a known image editor |
| `META_EDIT_HISTORY` | HIGH | XMP carries an edit history or `DerivedFrom` reference |
| `TAMPERING_CORROBORATED` | HIGH | Three or more distinct techniques each found substantive evidence |
| `NOISE_UNNATURALLY_SMOOTH` | MEDIUM | A contiguous region carries far less sensor noise than the rest |
| `NOISE_FOREIGN_REGION` | MEDIUM | A contiguous region carries a markedly different noise level |
| `META_TIMESTAMP_INCONSISTENT` | MEDIUM | Modified more than 60 s after capture |
| `IMAGE_UNDECODABLE` | MEDIUM | The upload could not be decoded, so pixel forensics could not run |
| `META_NO_CAMERA_INFO` | LOW | No camera make or model recorded |
| `META_UNREADABLE` | LOW | Metadata could not be parsed |

## Module 4 — Face Verification

| Code | Severity | Raised when |
|---|---|---|
| `FACE_MISMATCH` | CRITICAL | Similarity below the mismatch threshold (default 0.55) |
| `FACE_NOT_FOUND_ON_DOCUMENT` | HIGH | No portrait located on the document image |
| `FACE_INCONCLUSIVE` | MEDIUM | Similarity between the two thresholds |
| `FACE_NOT_FOUND_IN_CAPTURE` | MEDIUM | No face located in the live capture |

## Watchlist and identity screening

| Code | Severity | Raised when |
|---|---|---|
| `WATCHLIST_HIT_DOCUMENT` | Per entry (default CRITICAL) | Document number matches an active watchlist entry |
| `WATCHLIST_HIT_IDENTITY` | Per entry (default CRITICAL) | Identity matches an active watchlist entry |
| `DOCUMENT_USED_BY_OTHER_IDENTITY` | HIGH | This document number was previously presented under a different identity |
| `IDENTITY_USING_MULTIPLE_DOCUMENTS` | HIGH | This person previously presented other document numbers |
| `DOCUMENT_PRESENTATION_VELOCITY` | MEDIUM | Presented unusually often within 24 hours |

## Worked examples

**Genuine passport** — one `LOW` metadata note.
`1 − 0.94 = 0.06` → **6, LOW, CLEAR**

**Forged passport** — altered date of birth and a pasted region:
`MRZ_CHECKDIGIT_MISMATCH` (HIGH), `MRZ_COMPOSITE_MISMATCH` (HIGH),
`DOCUMENT_USED_BY_OTHER_IDENTITY` (HIGH), `COPY_MOVE_DUPLICATION` (MEDIUM),
`META_NO_CAMERA_INFO` (LOW).
`1 − (0.68 × 0.68 × 0.68 × 0.85 × 0.94) = 0.749` → **75, CRITICAL, REJECT**

Both figures are actual outputs from the running system.
