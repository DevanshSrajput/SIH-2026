/*
 * Officer-facing wording for machine finding codes.
 *
 * The raw code is what makes a case auditable months later, so it is never thrown away -
 * it stays visible next to the plain wording. But `DATAPAGE_MRZ_SURNAME_MISMATCH` is not
 * something a traveller can be read out at a desk, and an officer under time pressure
 * should not have to decode it. Each entry gives a short title and a sentence saying what
 * the rule actually compared, so the recommendation can be explained and challenged.
 *
 * A code with no entry falls back to a de-underscored version of itself, so adding a flag
 * in the backend never leaves a blank in the console.
 */

const FINDINGS = {
  // ---- Module 1: reading the document -------------------------------------
  MRZ_NOT_FOUND: {
    title: 'No machine-readable zone found',
    plain:
      'The two lines of code at the foot of the document could not be located. Either the ' +
      'document has none, or the image was too small, blurred or cropped to read it.',
  },
  OCR_LOW_CONFIDENCE: {
    title: 'Text was read with low confidence',
    plain:
      'The reader was unsure of the characters it returned. Treat the extracted fields as ' +
      'provisional and re-capture the image if anything looks wrong.',
  },

  // ---- Module 2: does the document obey its own standard? -----------------
  MRZ_CHECKDIGIT_MISMATCH: {
    title: 'Check digit does not match',
    plain:
      'Every machine-readable zone carries arithmetic check digits over its own data. One ' +
      'of them does not add up, which happens when a field has been altered - or misread.',
  },
  MRZ_COMPOSITE_MISMATCH: {
    title: 'Overall check digit does not match',
    plain:
      'The final check digit covers the whole second line at once. It disagrees with the ' +
      'data printed above it.',
  },
  UNKNOWN_ISSUING_STATE: {
    title: 'Issuing country is not a real country code',
    plain:
      'The three-letter issuing state is not on the ISO 3166 list or the ICAO special list. ' +
      'Forged documents often carry a plausible-looking country that does not exist.',
  },
  UNKNOWN_NATIONALITY: {
    title: 'Nationality is not a real country code',
    plain: 'The nationality field does not correspond to any recognised state.',
  },
  DOCUMENT_EXPIRED: {
    title: 'Document has expired',
    plain: 'The expiry date printed on the document has already passed.',
  },
  DOCUMENT_EXPIRING_SOON: {
    title: 'Document expires shortly',
    plain:
      'Still valid today, but close enough to expiry that onward travel rules may refuse it.',
  },
  EXPIRY_BEFORE_BIRTH: {
    title: 'Expires before the holder was born',
    plain: 'The dates are impossible in sequence. This cannot be a correctly issued document.',
  },
  ISSUE_AFTER_EXPIRY: {
    title: 'Issued after it expires',
    plain: 'The issue date falls after the expiry date, which no issuing authority produces.',
  },
  DOB_IN_FUTURE: {
    title: 'Date of birth is in the future',
    plain: 'The holder would not yet have been born. The field is wrong or fabricated.',
  },
  DOB_IMPLAUSIBLE: {
    title: 'Date of birth is implausible',
    plain: 'The implied age falls outside any range a living traveller could have.',
  },
  VALIDITY_PERIOD_EXCEEDS_STANDARD: {
    title: 'Valid for longer than the standard allows',
    plain:
      'The gap between issue and expiry is longer than the issuing standard permits for ' +
      'this document type.',
  },
  INVALID_SEX_CODE: {
    title: 'Sex field is not a permitted value',
    plain: 'The standard allows M, F or an unspecified marker. This document carries none of them.',
  },
  DOCUMENT_NUMBER_FORMAT: {
    title: 'Document number is not in the expected format',
    plain: 'The number does not match the pattern the issuing state uses.',
  },
  MANDATORY_FIELD_MISSING: {
    title: 'A required field is missing',
    plain:
      'A field every document of this type must carry could not be read. Often a symptom of ' +
      'a poor image rather than a forgery - check the capture before concluding anything.',
  },
  DATAPAGE_MRZ_SURNAME_MISMATCH: {
    title: 'Printed surname differs from the coded surname',
    plain:
      'The name printed on the page and the name encoded in the machine-readable zone should ' +
      'be the same. They are not.',
  },
  DATAPAGE_MRZ_GIVENNAMES_MISMATCH: {
    title: 'Printed given names differ from the coded given names',
    plain: 'The given names printed on the page do not match those in the machine-readable zone.',
  },
  DATAPAGE_MRZ_DOB_MISMATCH: {
    title: 'Printed date of birth differs from the coded one',
    plain:
      'A classic sign of an altered data page: the printed date was changed but the coded ' +
      'line was not.',
  },
  DATAPAGE_MRZ_EXPIRY_MISMATCH: {
    title: 'Printed expiry differs from the coded expiry',
    plain: 'The expiry date printed on the page disagrees with the machine-readable zone.',
  },
  DATAPAGE_MRZ_DOCNUMBER_MISMATCH: {
    title: 'Printed document number differs from the coded one',
    plain: 'The number printed on the page disagrees with the machine-readable zone.',
  },
  VISA_EXPIRED: { title: 'Visa has expired', plain: 'The visa is no longer valid for entry.' },
  VISA_NOT_YET_VALID: {
    title: 'Visa is not yet valid',
    plain: 'The visa becomes valid on a later date than today.',
  },
  VISA_VALID_FROM_AFTER_UNTIL: {
    title: 'Visa validity dates are reversed',
    plain: 'The "valid from" date falls after the "valid until" date.',
  },
  VISA_STAY_EXCEEDS_VALIDITY: {
    title: 'Permitted stay is longer than the visa is valid',
    plain: 'The stay duration granted runs past the end of the visa itself.',
  },
  VISA_UNKNOWN_ENTRY_TYPE: {
    title: 'Visa entry type is not recognised',
    plain: 'The number of entries granted is not one of the permitted values.',
  },

  // ---- Module 3: has the image been altered? ------------------------------
  ELA_SPLICE_SUSPECTED: {
    title: 'A region may have been pasted in',
    plain:
      'Error level analysis found an area that compresses differently from the rest of the ' +
      'image, which is what a spliced-in photo or field looks like.',
  },
  COPY_MOVE_DUPLICATION: {
    title: 'Part of the image is duplicated',
    plain:
      'One area appears to be a copy of another. Forgers clone background patches to cover ' +
      'text they want to hide.',
  },
  NOISE_FOREIGN_REGION: {
    title: 'One area has the wrong sensor noise',
    plain:
      'Every camera leaves a faint grain across the whole frame. One region carries a ' +
      'different grain, suggesting it came from a different source image.',
  },
  NOISE_UNNATURALLY_SMOOTH: {
    title: 'An area is unnaturally smooth',
    plain:
      'A region has almost no sensor noise at all, consistent with retouching or a synthetic ' +
      'fill rather than a photograph.',
  },
  META_EDITING_SOFTWARE: {
    title: 'File was written by image-editing software',
    plain:
      'The metadata names an editor rather than a camera or scanner. Not proof of forgery, ' +
      'but a genuine capture usually does not carry it.',
  },
  META_EDIT_HISTORY: {
    title: 'File carries an editing history',
    plain: 'The file records that it was modified after it was first created.',
  },
  META_TIMESTAMP_INCONSISTENT: {
    title: 'Timestamps disagree with each other',
    plain: 'The creation and modification times in the file are not consistent.',
  },
  META_NO_CAMERA_INFO: {
    title: 'No camera details recorded',
    plain:
      'No make or model in the metadata. Normal for a screenshot, a re-saved file or a scan, ' +
      'so weak on its own.',
  },
  META_UNREADABLE: {
    title: 'Metadata could not be read',
    plain: 'The file carries no readable metadata, so that line of evidence is unavailable.',
  },
  IMAGE_UNDECODABLE: {
    title: 'Image could not be decoded',
    plain:
      'The forensic checks could not open this image format, so tampering analysis did not ' +
      'run on it. Supply a JPEG or PNG.',
  },
  TAMPERING_CORROBORATED: {
    title: 'Several independent detectors agree',
    plain:
      'More than one forensic method flagged the same region. Independent methods agreeing ' +
      'is far stronger than any one of them alone.',
  },

  // ---- Module 4: is the bearer the holder? --------------------------------
  FACE_MISMATCH: {
    title: 'Face does not match the document photo',
    plain: 'The live capture and the photo on the document scored below the match threshold.',
  },
  FACE_INCONCLUSIVE: {
    title: 'Face comparison was inconclusive',
    plain: 'The similarity fell in the uncertain band. Treat it as unproven, not as a match.',
  },
  FACE_NOT_FOUND_ON_DOCUMENT: {
    title: 'No face found on the document',
    plain: 'No portrait could be located in the document image, so no comparison was possible.',
  },
  FACE_NOT_FOUND_IN_CAPTURE: {
    title: 'No face found in the live capture',
    plain: 'No face could be located in the camera image. Re-capture the traveller.',
  },

  // ---- Watchlist and cross-case history -----------------------------------
  WATCHLIST_HIT_DOCUMENT: {
    title: 'Document is on the watchlist',
    plain: 'This document number matches an active watchlist entry.',
  },
  WATCHLIST_HIT_IDENTITY: {
    title: 'Identity is on the watchlist',
    plain: 'This name and date of birth match an active watchlist entry.',
  },
  DOCUMENT_USED_BY_OTHER_IDENTITY: {
    title: 'Same document, different identity',
    plain:
      'This document number has been presented before under a different name. Either an ' +
      'impostor is using it, or an earlier scan misread the name.',
  },
  IDENTITY_USING_MULTIPLE_DOCUMENTS: {
    title: 'Same person, multiple documents',
    plain: 'This name and date of birth have been presented with more than one document number.',
  },
  DOCUMENT_PRESENTATION_VELOCITY: {
    title: 'Presented unusually often',
    plain:
      'This document has appeared more times in a short window than one traveller plausibly ' +
      'could, which can indicate a document being passed between people.',
  },

  // ---- Liveness detection -----------------------------------------------
  FACE_LIVENESS_SUSPICIOUS: {
    title: 'Liveness check suspicious',
    plain:
      'The live capture may be a printed photograph rather than a live person. ' +
      'The texture variance is unusually low, which is consistent with a flat image.',
  },
  FACE_MULTIPLE_FACES: {
    title: 'Multiple faces in live capture',
    plain:
      'More than one face was detected in the live capture. Only one person ' +
      'should be visible during face authentication.',
  },
  FACE_TOO_SMALL: {
    title: 'Face too small in frame',
    plain:
      'The detected face is very small relative to the image. Hold the document ' +
      'closer to the camera for a better comparison.',
  },
}

const MODULES = {
  OCR_EXTRACTION: { label: 'Reading the document', note: 'Module 1 - extraction' },
  DOCUMENT_VALIDATION: { label: 'Checking the rules', note: 'Module 2 - validation' },
  TAMPERING_DETECTION: { label: 'Looking for tampering', note: 'Module 3 - image forensics' },
  FACE_VERIFICATION: { label: 'Verifying the bearer', note: 'Module 4 - face match' },
  WATCHLIST: { label: 'Watchlist and history', note: 'Cross-case checks' },
}

const SEVERITY_ORDER = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3, INFO: 4 }

/** Title-cases a code we have no entry for, so nothing ever renders as raw SCREAMING_SNAKE. */
function humanise(code) {
  if (!code) return 'Finding'
  const words = String(code).replace(/_/g, ' ').toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

export function findingTitle(code) {
  return FINDINGS[code]?.title ?? humanise(code)
}

export function findingPlain(code) {
  return FINDINGS[code]?.plain ?? null
}

export function moduleLabel(module) {
  return MODULES[module]?.label ?? humanise(module)
}

export function moduleNote(module) {
  return MODULES[module]?.note ?? null
}

/** Most severe first. Flags of equal severity keep the order the backend sent them in. */
export function bySeverity(flags = []) {
  return [...flags].sort(
    (a, b) => (SEVERITY_ORDER[a.severity] ?? 9) - (SEVERITY_ORDER[b.severity] ?? 9),
  )
}

/** Human label for a field key inside an evidence map. */
export function evidenceLabel(key) {
  return humanise(key.replace(/([a-z])([A-Z])/g, '$1 $2'))
}
