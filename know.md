# Know This Project

Everything worth understanding about the AI-Based Fake Identity & Document Screening
System — what it is, why it exists, what makes it different, and the reasoning behind the
decisions that shaped it.

Written to be readable without the code open. Technical where it needs to be, plain
English everywhere else.

---

## 1. What this is

A border officer has roughly **thirty seconds** per traveller. In that time they must read
a passport, decide whether it is genuine, and decide whether the person holding it is its
owner. They do this a few hundred times a shift.

This system does the mechanical part of that job in about a second and hands the officer a
short, explained answer.

**The everyday scenario:**

> A traveller hands over a passport at Lane 4. The officer puts it on the scanner and
> clicks a button. A webcam takes the traveller's photo.
>
> One second later the screen says:
>
> > **🟡 REVIEW — risk score 47**
> > *The date of birth printed on the data page does not match the date encoded in the
> > machine-readable zone (1994-03-12 printed, 1989-03-12 encoded). The machine-readable
> > zone's check digit is valid, so the encoded value is the original — the printed date
> > appears to have been altered.*
>
> The officer now knows exactly where to look, and why.

That last part is the whole design. The system does not say "suspicious." It says what it
measured, what it expected, and what it found instead.

---

## 2. Why it exists

### The problem, in numbers

Indian land border checkpoints process thousands of documents a day. Verification today
rests on two things: an officer's eye, and a database lookup on the document number.

Both have gaps:

| What gets missed | Why an officer misses it |
|---|---|
| A photograph swapped on a genuine passport | The document is real. Only the face is wrong, and the officer has seconds to compare. |
| A date of birth altered by one digit | Nothing looks wrong. The inconsistency is with a machine-readable strip nobody reads by eye. |
| A digitally edited scan | Editing artefacts are invisible without forensic analysis. |
| The same person under three identities | Each document is individually valid. The pattern only appears across cases. |
| The same document presented six times in a day | Nobody is counting. |

And the pressure runs the wrong way: at a busy checkpoint, the cost of looking closely is
a growing queue, so the incentive is always to wave through.

### What this changes

The system removes the parts of the job that are mechanical — check digits, date
arithmetic, country codes, image forensics, face comparison, cross-referencing past cases
— and leaves the officer with judgement, which is the part a human is actually better at.

It does **not** make the decision. It produces a recommendation with reasons; the officer
decides, and their decision is recorded separately from the machine's, so a disagreement
between the two stays visible afterwards.

---

## 3. The USP — it knows when it does not know

Most screening tools output a confidence number. This one outputs **three possible
answers**, and the third is the point:

| Answer | Meaning |
|---|---|
| **MATCH** / **CLEAR** | The evidence supports it |
| **NO_MATCH** / **REJECT** | The evidence contradicts it |
| **UNCERTAIN** / **REVIEW** | The evidence does not settle it — *and here is exactly why* |

Why this matters more than it sounds:

> A face-matching system reports **"82% match"**.
>
> What should the officer do? 82% of what? Is 82% high? The number is real, but it is not
> *usable* — and a number that looks authoritative but cannot be acted on is worse than no
> number at all, because people act on it anyway.

This system instead says:

> **Cannot determine — officer check required**
> *The live capture is too blurred to compare reliably (sharpness 31, minimum 45).*
> **Next step:** Hold still and re-capture.

That is actionable in a way a percentage never is.

### The rule that enforces it

A positive face identification is issued **only** when all five hold:

1. The similarity score is decisive, and not sitting on the threshold
2. The document photo is good enough to judge
3. The live photo is good enough to judge
4. The live photo passed the anti-spoofing check
5. Exactly one face was in frame

Fail any one, and the answer is UNCERTAIN — **no matter how high the score is.** A 0.95
similarity on a blurred photo returns UNCERTAIN, not MATCH.

**The asymmetry is deliberate.** A wrong "yes" lets an impostor through on someone else's
passport. A wrong "not sure" costs an officer a few seconds. These are not equally bad
mistakes, and the thresholds are not set as though they were.

The same principle runs backwards too: the system will not call someone an impostor on the
strength of a dark, blurred photograph either. Bad evidence produces "I cannot tell",
never an accusation.

---

## 4. What it actually does — the five checks

Each document goes through four modules plus a cross-case stage.

### Module 1 — Read the document

Pulls out name, document number, nationality, date of birth, expiry, gender, and visa
terms.

It reads the **machine-readable zone** (the two lines of `<<<` characters at the bottom of
a passport) to the ICAO 9303 standard, supporting all five formats: TD1, TD2, TD3, MRV-A,
MRV-B. It *also* reads the printed labels separately, and keeps both.

Keeping both is the trick — see the tampering example below.

### Module 2 — Check the details follow the rules

Not "does this look right", but "does this satisfy the published standard":

- **Check digits.** The MRZ has arithmetic built in. Each field carries a check digit
  computed from its own characters with repeating weights of 7, 3, 1.

  > If a forger changes a birth date from `890312` to `940312`, the stored check digit no
  > longer matches the recomputed one. The alteration is caught by arithmetic — no AI, no
  > guessing, no false positives. Either the sum matches or it does not.

- **Chronology.** Expired? Expiring within 6 months? Born in the future? Issued before the
  holder was born? Implausible age?
- **Country codes** validated against the ISO 3166-1 alpha-3 list.
- **Cross-check** between the printed data page and the MRZ.
- **Visa terms**: stay duration against validity window, entry count.

### Module 3 — Look for tampering

Four independent forensic detectors, because each catches a different attack:

| Detector | Catches | How |
|---|---|---|
| **Error Level Analysis** | A pasted photo | Re-saves the image at a known JPEG quality. Regions edited and re-saved once already compress differently from the original — a pasted photograph lights up as a rectangle. |
| **Metadata forensics** | Editing software | Reads EXIF. A "scan" whose metadata says *Adobe Photoshop* is a finding on its own. |
| **Noise consistency** | Spliced regions | Every camera sensor leaves a characteristic noise fingerprint. Content from a different source carries different noise. |
| **Copy-move** | Cloned regions | Finds blocks duplicated elsewhere in the same image — the signature of covering a stamp by cloning blank paper over it. |

> **Why four and not one:** photo replacement, text editing and stamp cloning leave
> different traces. A single detector tuned to catch all three catches none of them well.

**The hard part is not detection — it is false positives.** Identity documents are covered
in deliberately repeated security printing: guilloche patterns, microtext, repeated
holograms. A naive copy-move detector flags every one of them as a cloned region. Every
threshold in this module exists because a genuine document tripped it.

### Module 4 — Is this the right person?

Compares the document portrait with a live camera capture.

Two models, both from the OpenCV Zoo:
- **YuNet** finds the face and five landmarks (eyes, nose, mouth corners)
- **SFace** turns the face into 128 numbers — an "embedding"

Two photographs of the same person produce similar embeddings. The similarity is the
cosine between them.

Before any comparison, both images go through a **quality gate**: resolution, focus,
exposure, contrast, head angle, detector confidence. Every check must pass. A face at mean
brightness 32 is too dark to identify anyone on, however sharp it is.

Then a **liveness check** looks for signs the "live" photo is actually a printed
photograph or a phone screen — texture flatness, halftone patterns in the frequency
domain, moiré, unnatural colour distribution, sensor noise uniformity.

### Cross-case stage — patterns across documents

The checks above look at one document. This looks across all of them:

- **Watchlist**: stolen, revoked, entry-ban, wanted, visa-overstay lists
- **Document reuse**: the same document number previously presented under a *different*
  name
- **Multiple identities**: the same person (surname + date of birth) previously presented
  under *different* document numbers
- **Velocity**: the same document presented 3+ times in 24 hours

> A perfectly genuine passport presented by four different people over two days is invisible
> to any per-document check. It is obvious to this one.

---

## 5. Unique features

### 5.1 Every finding carries its evidence

A finding is not a label. It is a label plus the measurement behind it.

```json
{
  "code": "MRZ_CHECK_DIGIT_MISMATCH",
  "severity": "HIGH",
  "message": "The date of birth check digit does not match the encoded date.",
  "evidence": {
    "field": "dateOfBirth",
    "encodedValue": "890312",
    "printedCheckDigit": "4",
    "recomputedCheckDigit": "7"
  }
}
```

**Why this matters:** a case rejected today may be challenged in eighteen months. "The
system flagged it" is not a defence. "The check digit was 4 and should have been 7" is.

### 5.2 Risk combines like evidence, not like points

The obvious approach is to add up points. This system multiplies:

```
score = 100 × (1 − Π(1 − weightᵢ))
```

Each finding eats into the remaining probability that the document is sound.

**Worked example** — a document with five MEDIUM findings (weight 15 each):

| Method | Calculation | Score | Verdict |
|---|---|---|---|
| Adding points | 15 × 5 | **75** | 🔴 REJECT |
| This system | 100 × (1 − 0.85⁵) | **56** | 🟡 REVIEW |

And one CRITICAL finding (weight 70) alone scores **70** — REJECT.

> So five minor observations do not out-weigh one decisive one. That is the intended
> behaviour: **a document must never be rejected by accumulated trivia.** Under an additive
> model, enough small irregularities — a worn passport, a missing optional field, a
> low-resolution scan — eventually reject an honest traveller. Under this one, they cannot.

Three properties follow, all of which matter at a checkpoint:

- **It saturates** — trivia cannot out-score decisive evidence
- **It is monotonic** — more evidence never *lowers* the score
- **It is order-independent** — modules can run in any order, or in parallel, and the
  verdict does not move

### 5.3 Missing evidence is not absence of evidence

If a module *fails*, the system cannot return CLEAR. Ever.

> The tampering detector crashes on a malformed image. Three modules found nothing wrong.
> Is the document clean?
>
> Unknown. Nobody checked. So the verdict is REVIEW, not CLEAR.

A module that is *skipped* (never configured) is different from one that *failed* (tried
and broke), and the system distinguishes them. The officer sees which.

### 5.4 It never invents a measurement

If no face matcher is configured, Module 4 reports **"did not run"**. It does not estimate
a plausible-looking similarity.

> This sounds obvious and is routinely violated. A screening decision must not rest on a
> number that only *looks* like a measurement.

### 5.5 Live quality feedback at the desk

While the officer is lining the traveller up, the camera preview samples the frame once a
second and says what is wrong **before** the photo is taken:

> *"Move closer to the camera"* · *"Hold still — the image is blurred"* · *"2 faces in
> frame"* · *"Ready to capture"*

Without it, the officer submits a bad photo, waits, gets "cannot determine", and by then
the traveller has moved. With it, the problem is fixed in two seconds.

### 5.6 Threshold calibration, shipped as a tool

`calibrate.py` takes a folder of real photographs and reports how often the system would
wrongly declare two different people the same person — on *your* cameras, *your* lighting,
*your* scanners. If that rate is not zero, it tells you what threshold to use instead.

> A threshold copied from a research paper is a guess about someone else's hardware.

### 5.7 Everything runs offline

No cloud service is required. OCR uses local Tesseract; face matching runs a local model;
the models are baked into the Docker image at build time.

> A border post is exactly the place that cannot assume working internet. A system that
> degrades silently when the link drops is not deployable there.

### 5.8 Complete audit trail

Every screening, every officer decision, every watchlist edit, every face enrolment is
recorded with who, when, and what the system recommended at the time. Evidence images are
stored **before** analysis, so a crash mid-pipeline still leaves them recoverable.

---

## 6. Key decisions, and why

Each of these had a plausible alternative. The reasoning is what matters.

### Decision 1 — Reading and judging are strictly separated

**The choice:** OCR engines only *read*. They never decide whether a document is genuine.
Every judgement is made by deterministic, explainable code in Modules 2–4.

**The alternative:** ask a vision model "is this passport fake?"

**Why not:** because the answer cannot be defended. When an officer asks *why*, "the model
said so" is not an answer, and a screening decision that cannot be explained cannot be
challenged, audited, or corrected.

> The MRZ check digit either matches or it does not. That is a fact an officer can verify
> by hand, in front of the traveller, in ten seconds.

### Decision 2 — Three answers, never two

**The choice:** MATCH / NO_MATCH / **UNCERTAIN**, everywhere a judgement is made.

**The alternative:** a similarity score and a single threshold.

**Why not:** a single threshold forces every borderline case into one of two bins and
throws away the information that it *was* borderline — which is the most useful thing to
know about it.

> Score 0.461, threshold 0.46 → "MATCH". Score 0.459 → "MISMATCH". Two nearly identical
> measurements, two opposite conclusions, both stated with total confidence. Neither is
> honest.

### Decision 3 — Face similarity is a raw cosine, never rescaled

**The choice:** report the raw cosine similarity between embeddings.

**The alternative:** map it onto 0–100% so it reads like a confidence.

**Why not — this is the single most important technical decision in the project.** An
earlier version did exactly that, with `(cosine + 1) / 2`. It looks harmless:

| Reality | Raw cosine | After rescaling |
|---|---|---|
| Two different people | 0.10 | **0.55** |
| Same person | 0.60 | **0.80** |

The rescaling compressed the whole useful range into roughly 0.45–0.90 and pushed two
complete strangers to "55% similar" — which landed inside the old match/review band.

> **It was a false-accept generator disguised as a friendlier number.**

Raw cosine keeps the two populations where they actually sit:

| Population | Typical raw cosine |
|---|---|
| Two different people | 0.00 – 0.25 |
| Same person, scan vs live capture | 0.40 – 0.75 |

The match threshold is **0.46**, deliberately above SFace's published 0.363 break-even
point. That break-even balances false accepts against false rejects; a checkpoint does not
want them balanced.

### Decision 4 — Image quality is a gate, not a score adjustment

**The choice:** a poor-quality image is never decided on at all.

**The alternative:** lower the confidence and carry on.

**Why not:** a blurred face does not produce a *less certain* embedding. It produces a
*wrong* one — and the error has no reliable direction. It can move a stranger's face
closer to yours just as easily as further away.

> Lowering confidence implies the answer is still roughly right. It is not. The only honest
> response is to refuse and ask for a better photo.

Every individual check must pass, not just the average:

> A face at mean brightness 32 — far too dark to identify anyone on — still *averages* well
> above the bar once good resolution and sharpness are folded in. It would sail through
> with its failed exposure check recorded and ignored. An attribute measured out of range
> is a reason not to decide, whatever the other attributes say.

### Decision 5 — 1:N identification is stricter than 1:1 verification

**The choice:** a higher threshold, plus the top candidate must beat the runner-up by a
clear margin.

**The alternative:** reuse the 1:1 threshold.

**Why not:** verification makes one comparison. Identification compares against everyone
enrolled — so **every additional person in the gallery is another chance for a coincidental
high score.** Run a 1:1 threshold against a gallery of ten thousand and it will name
someone eventually.

> Two candidates scoring 0.61 and 0.58 is not an identification. It is a pair of
> lookalikes, and the system says `AMBIGUOUS` rather than picking the higher one.

### Decision 6 — The in-process face fallback was deleted, not kept

**The choice:** one face matcher — the dedicated service.

**The alternative:** keep the OpenCV fallback for when the service is down.

**Why not:** its detector reported a bounding box and no facial landmarks. SFace is trained
on faces warped to a canonical layout *using* those landmarks, so the fallback could only
feed it a plain resized crop — and an unaligned crop shifts every embedding in the same
direction, dragging unrelated faces together.

It could never confirm an identity. It also required 45 MB of models that were never
shipped, and pulled in the heaviest dependency in the entire build.

> A fallback that cannot answer the question is not a fallback. Keeping it would have meant
> keeping something that *looked* like a safety net.

With it gone, if the face service is down, Module 4 reports FAILED — which is true, visible,
and prevents a CLEAR verdict.

### Decision 7 — Names go through ICAO transliteration

**The choice:** compare names using the ICAO 9303 transliteration rules.

**The alternative:** strip accents and compare.

**Why not:** ICAO *expands* characters rather than stripping them. `Ü` becomes `UE`, `ß`
becomes `SS`.

> Passport MRZ: `MUELLER`. Printed page: `MÜLLER`.
>
> Strip-the-accent gives `MULLER` ≠ `MUELLER` → **forgery flag raised against every German
> traveller in the queue.**

A false positive that fires reliably against one nationality is worse than useless — it
teaches officers to ignore the system.

### Decision 8 — Watchlist entries are deactivated, never deleted

**The choice:** withdrawing an entry marks it inactive.

**The alternative:** delete the row.

**Why not:** a watchlist is evidence. Deleting a row erases the record that a document was
ever flagged — and with it, the justification for every past case that was rejected because
of it.

The same reasoning applies to face enrolments and to officer decisions.

### Decision 9 — A name alone cannot put someone on a watchlist

**The choice:** an entry requires a document number, **or** a surname *together with* a date
of birth.

**Why:** common surnames would stop travellers who merely share a name with someone on the
list. That is worse than missing a hit, because it happens repeatedly, to the same innocent
people, and they have no way to clear it.

### Decision 10 — Bulk imports apply row by row

**The choice:** a malformed row is rejected and reported; the rest of the file still loads.

**The alternative:** reject the whole file if any row is bad.

**Why not:** a feed from an upstream agency routinely carries a handful of records that fail
validation. Rejecting the entire file over three bad rows leaves the whole watchlist
un-updated — the failure mode is silent and much worse than the problem it avoids.

### Decision 11 — Colour never carries meaning alone

**The choice:** every coloured element also carries a word, a shape, or a position.

**Why:** roughly **one officer in twelve** cannot reliably separate red from amber. The
console's CLEAR-green and REVIEW-amber measure only ΔE 5.1 apart under protanopia — not
separable.

So verdicts always carry their label, severity shows as a left edge as well as a hue, the
volume chart uses hatch textures as well as colour, and every chart has a table view.

> A red-green colourblind officer reading a screening verdict is not an edge case. It is
> Tuesday.

### Decision 12 — Rate limiting applies to writes only

**The choice:** cap expensive write requests; never limit reads.

**Why:** screening is expensive — OCR, four forensic detectors, a neural face comparison. A
loop hitting the endpoint stalls the lanes processing real travellers.

But an officer refreshing a case list is not a threat, and **throttling the console
mid-shift is a worse failure than the flood it prevents.**

---

## 7. What it deliberately does not do

Being clear about the boundary is part of being trustworthy.

| Not done | Why |
|---|---|
| **Decide anything** | It recommends. The officer decides, and their decision is stored separately, so a disagreement stays visible. |
| **Verify the e-passport chip** | Chip verification needs the ICAO PKD and country signing certificates — a separate PKI problem, not an image problem. |
| **Detect a high-quality 3D mask** | The liveness checks catch printed photos and phone screens. Defeating a good mask needs depth or infrared hardware. |
| **Replace the database lookup** | It adds analysis on top of INTERPOL/national lookups; it does not substitute for them. |
| **Work out of the box on real travellers** | The face thresholds need calibrating on real photographs first. This is stated everywhere it is relevant, including here. |

---

## 8. Honest limitations

1. **The face thresholds are not yet calibrated on real photographs.** The shipped values
   are reasoned defaults, not measurements. `calibrate.py` exists precisely to fix this,
   and it must be run before the system is relied on.
2. **There is no authentication.** Every endpoint is open. This is a prototype; it needs
   login and role-based access before touching a network.
3. **Biometric data is stored unencrypted.** Face embeddings and passport numbers need
   encryption at rest.
4. **Rate limiting is per-instance.** The counter lives in memory, so behind a load balancer
   the effective limit multiplies by the number of instances.
5. **Tampering detection has not been tested against real forgeries.** The detectors are
   sound in principle and tuned against genuine documents to suppress false positives, but
   no confiscated forgeries were available to test against.

---

## 9. Three bugs worth knowing about

All three were in the face pipeline, all three were false-accept sources, and all three are
fixed. They are recorded because the *class* of mistake is instructive.

### Bug 1 — The rescaling described in Decision 3

`(cosine + 1) / 2` compressed the score range and pushed strangers into the match band.
**Lesson:** a transformation that makes a number friendlier to read can destroy the thing
the number was for.

### Bug 2 — An off-by-one in an array index

YuNet returns each detection as `[x, y, w, h, 10 landmark coordinates, score]`. **The score
is the last element.** The code read index 4 — which is the right eye's x-coordinate — and
took the landmarks from indices 5–14, shifting every one by half a coordinate.

Two consequences:

- Detector confidence came back as values like **233** instead of **0.71**, so the score
  threshold was comparing against nonsense and filtering nothing.
- More seriously, the shifted landmarks fed the alignment step a face warped to the wrong
  canonical position — degrading every embedding and pulling unrelated faces together.

**Lesson:** this produced no error, no exception, no crash. It surfaced only as *slightly
worse scores*. It was found by printing a raw detection row during end-to-end testing and
noticing that a "confidence" of 233 is not a probability. There is now a test pinning the
layout.

### Bug 3 — The wrong preprocessing constant

The deleted local verifier passed the *face detector's* Caffe mean `(104, 177, 123)` to
SFace, which takes raw pixels and wants no mean subtraction — shifting every embedding by a
constant.

**Lesson:** two models in the same file, with different input conventions, and a constant
that was correct twenty lines away.

---

## 10. The one-line version

> Most screening systems tell you how confident they are.
> **This one tells you when it should not be trusted — and exactly why.**
