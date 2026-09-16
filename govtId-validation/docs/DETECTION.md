# Detection Techniques

How each technique works, why it catches what it catches, and what defeats it. The last
column matters most: a screening platform whose operators do not know its blind spots will
be trusted in exactly the situations where it is wrong.

---

## MRZ check digits (Module 2)

**What it catches.** Any edit to the document number, date of birth, expiry date or
personal number on a document carrying a machine readable zone.

**How.** ICAO 9303 assigns each of those fields a check digit computed by weighting its
characters 7-3-1 cyclically (digits at face value, `A`–`Z` as 10–35, filler `<` as 0) and
taking the sum modulo 10. A final composite digit covers the concatenation of the checked
fields. All are recomputed and compared with what is printed.

**Why it works.** The MRZ is machine-oriented and looks like noise. A forger altering a
date on the visible data page rarely re-derives the checksum, leaving an arithmetic
contradiction inside the document itself.

**What defeats it.** A competent forger who recomputes the check digits. This is why the
data-page cross-check exists: consistency in the MRZ alone is not sufficient.

---

## Data page versus MRZ (Module 2)

**What it catches.** Partial alterations — the printed page edited while the MRZ is left
alone, or the reverse.

**How.** Module 1 records printed values separately from MRZ values. Module 2 compares
surname, given names, document number, date of birth and expiry.

**Care taken.** Comparison runs through ICAO transliteration before matching, so `MÜLLER`
against MRZ `MUELLER`, or `Straße` against `STRASSE`, is not reported. Under-normalising
here would raise a forgery finding against every traveller with a non-English name — a
failure mode that is both useless and discriminatory.

**What defeats it.** A forger who edits both readings consistently, and OCR that fails to
recover the printed values at all (in which case no comparison is made and no finding is
raised — a gap, not a pass).

---

## Error Level Analysis (Module 3)

**What it catches.** Photo replacement and text manipulation on JPEG documents.

**How.** The image is re-encoded at a fixed quality and differenced against itself. Every
JPEG save quantises and introduces a characteristic error; a region pasted from another
source has been through a different number of compression generations, so it errors at a
different level from its surroundings. Per-block errors are compared against the image
median.

**Two conditions, both required.** The anomalous blocks must be much brighter than the
median *and* spatially clustered. Scattered bright blocks are ordinary high-detail
content — printed text, guilloche. When more than 20% of the image reads as anomalous, the
image is simply lightly compressed or highly detailed, and nothing is reported.

**Output.** A heat map is returned with the finding and rendered in the console, so an
officer sees *where* the anomaly is rather than being told a score.

**What defeats it.** A lossless original (PNG, some scanners) — the technique has nothing
to measure and reports nothing rather than guessing. Also a forgery re-saved enough times
that every region reaches the same compression generation.

---

## Noise consistency (Module 3)

**What it catches.** Retouched, cloned or spliced regions.

**How.** Every capture device lays down a roughly uniform noise floor. Content pasted from
elsewhere brings its own noise level; a region smoothed or healed to hide an edit ends up
unnaturally clean. Noise is estimated per block from the Laplacian residual using a median
absolute deviation, so the hard edges of printed text do not inflate the estimate the way a
plain variance would.

**Care taken.** A tampered region is by definition a minority of the document. When more
than 15% of blocks read as anomalous, the image simply has a naturally uneven noise floor —
film grain, heavy print texture, aggressive in-camera denoising — and nothing is reported.
Without this guard the detector fires on ordinary documents.

**What defeats it.** Global denoising or re-noising of the whole image, which flattens the
very inconsistency being measured.

---

## Copy-move duplication (Module 3)

**What it catches.** Stamp forgery — the cheapest attack, where a genuine entry stamp or
endorsement is copied from elsewhere on the same document and pasted where it is wanted.

**How.** Overlapping 8×8 blocks are reduced to a coarse signature (block mean plus four
quadrant means, quantised). Blocks sharing a signature are paired, and each pair votes for
its displacement vector. A duplicated region makes one vector accumulate many votes.

**Three safeguards, each earned the hard way:**

1. **Flat blocks are discarded.** Blank paper matches itself everywhere.
2. **Oversubscribed signatures are dropped entirely, not truncated.** A signature shared by
   more than a handful of blocks belongs to repeated printing. Truncating such a bucket
   would keep whichever blocks were scanned first and silently discard the duplicate being
   searched for, because a pasted region is scanned last.
3. **Matches must form a compact, connected region of real size.** This is the safeguard
   that decides whether the detector is usable at all. Identity documents are *deliberately*
   covered in repeated line-art — guilloche, rosettes, microprinting — which genuinely does
   produce many matches at a consistent offset. What it does not produce is a solid filled
   patch tens of pixels across in both directions. Shape, not vote count, separates a forged
   stamp from security printing.

**Every well-supported shift is examined, not just the most popular one.** On a real
document the top-voted shift is usually the guilloche pitch, since security printing repeats
across the whole page while a forged stamp covers a small part of it. Judging only the top
shift would let heavy security printing mask an actual duplication.

**Resolution.** The search runs on native pixels. Smooth downscaling cannot be used: a
pasted region equals its source only in the original pixels, and interpolating to a
non-integer scale resamples the copy at a different subpixel phase, destroying the very
equality the technique depends on. Oversized images are decimated by a whole-number factor
instead.

**Compression tolerance.** A paste rarely lands at the same phase relative to the JPEG 8×8
grid as its source, so re-compression quantises the two copies differently. A light blur
before matching suppresses those blocking artefacts.

**What defeats it.** A stamp copied from a *different* document (nothing is duplicated
within this one), a pasted region that is rotated or rescaled, and heavy re-compression that
overwhelms the smoothing.

---

## Metadata forensics (Module 3)

**What it catches.** Images that have been through an editor.

**How.** EXIF and XMP are parsed for an editing-software signature in the `Software` tag, an
embedded edit history or `DerivedFrom` reference, absent camera make and model, and a
modification timestamp well after the capture timestamp.

**Weighting.** Deliberately asymmetric. An explicit editor signature is high severity —
that is the editor's own admission. Absent metadata is *low* severity, because legitimate
pipelines strip it constantly: scanners, capture kiosks, messaging apps and resize steps all
do. Treating stripped metadata as strong evidence would flag most honest documents.

**What defeats it.** Stripping or forging metadata, which takes one command. Metadata is
supporting evidence and is scored accordingly; it should never carry a decision alone.

---

## Corroboration

When three or more *distinct techniques* each raise a substantive finding, a
`TAMPERING_CORROBORATED` finding is added.

Corroboration is counted **per technique, not per finding**. Two findings from one detector
are one technique's opinion stated twice, and counting them as independent would manufacture
agreement the evidence does not support. Low-severity observations — notably absent
metadata — do not count towards corroboration at all.

---

## Cross-case detection (Watchlist stage)

**What it catches.** What no single document reveals: one document circulating between
people, one person accumulating documents, and abnormal reuse.

**How.** Every screening writes a normalised document key and identity key
(`SURNAME|GIVENNAMES|YYYY-MM-DD`) to case history. Subsequent screenings query against it.

**Care taken.** An identity key is only built when both a surname and a date of birth are
available. A name alone is far too common to key on: a partial key would produce false
matches, and in this context a false match means detaining the wrong traveller.

**What defeats it.** A first presentation — there is no history to compare against — and
identities that differ enough in spelling to survive normalisation.
