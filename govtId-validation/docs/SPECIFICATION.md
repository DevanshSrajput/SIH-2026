# Functional Specification

## Problem

Border checkpoints process thousands of identity and travel documents a day. Verification
today rests on human inspection and basic database lookups, which is slow, inconsistent
between officers and checkpoints, and poor at catching competent forgeries.

The recurring attacks are:

| Attack | Where it is caught |
|---|---|
| Forged passports and visas | Module 2 — check digits, standards conformance |
| Altered photographs | Module 3 — ELA, noise consistency |
| Modified dates of birth | Module 2 — MRZ check digits, data-page cross-check |
| Tampered visa stamps | Module 3 — copy-move detection |
| Identity impersonation | Module 4 — face verification |
| One person, several identities | Watchlist stage — cross-case identity history |
| Expired or blacklisted documents | Module 2 (expiry), watchlist stage (blacklist) |
| High passenger volume causing delays | Whole pipeline — ~0.5 s per document |

## Objectives

1. Cut document verification from minutes to seconds.
2. Detect forgery and tampering that a human inspector at a busy desk would miss.
3. Make screening decisions consistent across officers and checkpoints.
4. Replace impression with a risk score derived from stated, auditable rules.
5. Leave a digital trail usable for investigation and intelligence analysis.

## Non-objectives

- The system **recommends**; the officer decides. Both are recorded, separately.
- It does not authenticate chip signatures (passive/active authentication). That is a
  separate PKI problem — see [TASKS.md](TASKS.md).
- It does not perform identity resolution against national population registers.

---

## Module 1 — OCR Extraction

**Objective.** Recover every relevant field from the presented document.

**Inputs.** Passport, visa, national identity card, driving licence, or permit — as an
image. Optionally, text the caller already holds (an e-passport chip read or an MRZ keyed
in by the officer), which is trusted over pixel OCR.

**Method.** The highest-priority available reading engine runs, then the text is parsed:
the ICAO 9303 machine readable zone first, with printed labels filling only what the MRZ
did not supply. Printed values are *also* captured separately so Module 2 can compare the
two independent readings of the same document.

### Fields extracted

**Passport / national ID / licence / permit**

| Field | Source |
|---|---|
| Surname, given names | MRZ name field; printed labels as fallback |
| Document number | MRZ positions 1–9; printed label as fallback |
| Issuing state | MRZ, ISO 3166-1 alpha-3 |
| Nationality | MRZ, ISO 3166-1 alpha-3 |
| Date of birth | MRZ `YYMMDD`, century inferred backwards |
| Date of expiry | MRZ `YYMMDD`, century inferred forwards |
| Sex | MRZ, `M` / `F` / `X` |
| Personal number | MRZ optional data field |

**Visa**

| Field | Source |
|---|---|
| Visa number | MRV MRZ document-number position, or printed label |
| Visa type / class | Printed label |
| Entry type | Printed label, normalised to SINGLE / DOUBLE / MULTIPLE |
| Stay duration | Printed label, in days |
| Valid from / valid until | Printed labels |

**MRZ layouts supported.** TD1 (3×30), TD2 (2×36), TD3 (2×44, passports), MRV-A (2×44) and
MRV-B (2×36) visa stickers.

**Reporting.** Module 1 reports on the *quality* of the read — confidence, whether an MRZ
was found on a document required to carry one. Whether the values are consistent is
Module 2's question. Every field is nullable: a damaged document legitimately yields gaps,
and a gap is a finding, not an error.

---

## Module 2 — Document Validation

**Objective.** Establish whether the extracted values are internally consistent and conform
to the standards the issuing authority had to follow.

Every check is deterministic and explainable. The same inputs always produce the same
finding, and an officer can be told exactly which rule fired.

### Checks

**MRZ arithmetic** — every printed check digit is recomputed (7-3-1 weighting), plus the
composite. A forger who edits a printed date rarely re-derives the checksum.

**Data page vs MRZ** — a genuine document states the same identity twice. Surname, given
names, document number, date of birth and expiry are compared between the two readings.
Comparison passes through ICAO transliteration, so `MÜLLER` and `MUELLER` are the same name
and do not raise a finding.

**Chronology** — expired; expiring within six months; date of birth in the future; an
implausible age; expiry preceding birth; issue after expiry; validity period longer than
the ten-year passport maximum.

**Codes** — issuing state and nationality must be recognised ISO 3166-1 alpha-3 or ICAO
special codes (`D`, `XXA`–`XXC`, `GBD`–`GBS`, `UNO`, `UNA`, `EUE`, `RKS`). Sex must be
`M`, `F` or `X`.

**Format** — a passport number must fit ICAO's nine alphanumeric characters.

**Presence** — mandatory fields that could not be established are reported.

**Visa terms** — inverted validity window; expired; not yet valid; permitted stay longer
than the validity window; unresolvable entry type.

---

## Module 3 — Tampering Detection

**Objective.** Detect digitally or physically altered documents.

Four techniques run over the same image. They are deliberately uncorrelated and fail in
different ways, so agreement between them carries real weight — that agreement is itself
recorded as a finding, counted per technique rather than per observation.

| Use case from the brief | Technique |
|---|---|
| Photo replacement | Error Level Analysis; noise consistency |
| Text manipulation | Error Level Analysis; MRZ check digits (Module 2) |
| Stamp forgery | Copy-move duplication detection |
| Image metadata analysis | EXIF/XMP provenance forensics |

How each works, and what defeats it, is in [DETECTION.md](DETECTION.md).

---

## Module 4 — Face Verification

**Objective.** Establish that the person presenting the document is the person it was
issued to. This is the check that catches impersonation with a wholly genuine document.

**Method.** The document portrait and a live capture are compared by a biometric matching
service. Two thresholds, not one: above the match threshold the faces are accepted, below
the mismatch threshold they are treated as different people, and in between the case is
referred to an officer rather than decided. Biometric comparison is probabilistic and the
honest response to an ambiguous score is to say so.

**When no matcher is configured, the module reports that it did not run.** It does not
estimate a similarity. A screening decision must never rest on a number that merely looks
like a measurement.

---

## Watchlist and identity screening

Covers what a single document, examined alone, cannot reveal.

- **Blacklist hits** — stolen, revoked, entry-banned, wanted, or overstay records, matched
  on normalised document number or on surname plus date of birth.
- **One document, several people** — the same document number previously presented under a
  different identity.
- **One person, several documents** — the same name and date of birth previously presented
  with different document numbers.
- **Velocity** — the same document presented unusually often in 24 hours.

The last three are only visible because every screening is written to case history, which
is also what makes the trail available to investigators afterwards.

---

## Expected impact

| Goal | How it is met |
|---|---|
| Verification in seconds | ~0.5 s per document end to end |
| Better forgery detection | Four uncorrelated forensic techniques plus MRZ arithmetic |
| Standardised decisions | One deterministic rule set and scoring model across all checkpoints |
| Data-driven assessment | Every decision carries a score, a rule trail and the evidence behind it |
| Investigative trail | Append-only audit events plus retained evidence images per case |
