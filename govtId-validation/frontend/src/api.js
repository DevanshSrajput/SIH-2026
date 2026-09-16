/**
 * Thin client over the screening API.
 *
 * Errors are surfaced with the server's own message rather than a generic one: an
 * officer needs to know whether a screening failed because the image was too large or
 * because the service is down, and those demand different responses at the desk.
 */

const BASE = '/api'

// The face service is proxied in development (see vite.config.js) so the browser only
// ever sees one origin. Override for a deployment where it sits elsewhere.
const FACE_SERVICE = import.meta.env.VITE_FACE_SERVICE_URL || '/face-service'

async function handle(response) {
  if (response.ok) {
    return response.status === 204 ? null : response.json()
  }
  let message = `${response.status} ${response.statusText}`
  try {
    const body = await response.json()
    if (body?.message) {
      message = body.message
    }
  } catch {
    // Response had no JSON body; the status line is all we have.
  }
  throw new Error(message)
}

export function screenDocument({
  document,
  live,
  documentType,
  checkpointId,
  laneId,
  officerId,
  text,
}) {
  const form = new FormData()
  form.append('document', document)
  if (live) {
    form.append('live', live)
  }

  const params = new URLSearchParams({ documentType: documentType || 'UNKNOWN' })
  if (checkpointId) params.set('checkpointId', checkpointId)
  if (laneId) params.set('laneId', laneId)
  if (officerId) params.set('officerId', officerId)
  if (text) params.set('text', text)

  return fetch(`${BASE}/screenings?${params}`, { method: 'POST', body: form }).then(handle)
}

/**
 * Flattens a Spring `PagedModel` into the shape the pages read.
 *
 * PagedModel nests its metadata under `page`, where the older serialised `Page` put it at
 * the top level, and drops `first`/`last` entirely. Normalising here keeps that difference
 * out of every component, and keeps working if the backend is rolled back.
 */
function normalisePage(body) {
  if (!body) return { content: [], number: 0, size: 0, totalElements: 0, totalPages: 0, first: true, last: true }

  const meta = body.page ?? body
  const number = meta.number ?? 0
  const totalPages = meta.totalPages ?? 0

  return {
    content: body.content ?? [],
    number,
    size: meta.size ?? 0,
    totalElements: meta.totalElements ?? 0,
    totalPages,
    first: body.first ?? number === 0,
    last: body.last ?? number >= Math.max(0, totalPages - 1),
  }
}

export function listCases(page = 0, size = 25) {
  return fetch(`${BASE}/screenings?page=${page}&size=${size}`).then(handle).then(normalisePage)
}

export function getCase(reference) {
  return fetch(`${BASE}/screenings/${encodeURIComponent(reference)}`).then(handle)
}

export function getAudit(reference) {
  return fetch(`${BASE}/screenings/${encodeURIComponent(reference)}/audit`).then(handle)
}

export function imageUrl(reference, kind) {
  return `${BASE}/screenings/${encodeURIComponent(reference)}/images/${kind}`
}

export function recordDecision(reference, body) {
  return fetch(`${BASE}/screenings/${encodeURIComponent(reference)}/decision`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }).then(handle)
}

export function listWatchlist(page = 0, size = 50) {
  return fetch(`${BASE}/watchlist?page=${page}&size=${size}`).then(handle).then(normalisePage)
}

export function addWatchlistEntry(body) {
  return fetch(`${BASE}/watchlist`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }).then(handle)
}

export function deactivateWatchlistEntry(id, actor) {
  const params = actor ? `?actor=${encodeURIComponent(actor)}` : ''
  return fetch(`${BASE}/watchlist/${encodeURIComponent(id)}${params}`, {
    method: 'DELETE',
  }).then(handle)
}

export function getStats(windowHours = 24) {
  return fetch(`${BASE}/stats?windowHours=${windowHours}`).then(handle)
}

export function compareFaces({ document, live }) {
  const form = new FormData()
  form.append('document', document)
  form.append('live', live)

  return fetch(`${BASE}/face`, { method: 'POST', body: form }).then(handle)
}

export function updateWatchlistEntry(id, body) {
  return fetch(`${BASE}/watchlist/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  }).then(handle)
}

export function reactivateWatchlistEntry(id, actor) {
  const params = actor ? `?actor=${encodeURIComponent(actor)}` : ''
  return fetch(`${BASE}/watchlist/${encodeURIComponent(id)}/reactivate${params}`, {
    method: 'POST',
  }).then(handle)
}

export function importWatchlist(entries, actor) {
  const params = actor ? `?actor=${encodeURIComponent(actor)}` : ''
  return fetch(`${BASE}/watchlist/import${params}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(entries),
  }).then(handle)
}

export function watchlistExportUrl() {
  return `${BASE}/watchlist/export`
}

// -- Face enrolment and 1:N identification ---------------------------------

export function enrolFace({
  image,
  subjectId,
  displayName,
  documentNumber,
  nationality,
  source,
  enrolledBy,
  notes,
  acceptPoorQuality,
}) {
  const form = new FormData()
  form.append('image', image)

  const params = new URLSearchParams({ subjectId })
  if (displayName) params.set('displayName', displayName)
  if (documentNumber) params.set('documentNumber', documentNumber)
  if (nationality) params.set('nationality', nationality)
  if (source) params.set('source', source)
  if (enrolledBy) params.set('enrolledBy', enrolledBy)
  if (notes) params.set('notes', notes)
  if (acceptPoorQuality) params.set('acceptPoorQuality', 'true')

  return fetch(`${BASE}/face/enrolments?${params}`, { method: 'POST', body: form }).then(handle)
}

export function listEnrolments(page = 0, size = 50) {
  return fetch(`${BASE}/face/enrolments?page=${page}&size=${size}`).then(handle).then(normalisePage)
}

export function withdrawEnrolment(id, actor) {
  const params = actor ? `?actor=${encodeURIComponent(actor)}` : ''
  return fetch(`${BASE}/face/enrolments/${encodeURIComponent(id)}${params}`, {
    method: 'DELETE',
  }).then(handle)
}

export function identifyFace(image, limit = 5) {
  const form = new FormData()
  form.append('image', image)
  return fetch(`${BASE}/face/identify?limit=${limit}`, { method: 'POST', body: form }).then(handle)
}

/**
 * Quality-checks a single frame before it is submitted.
 *
 * Talks to the face service directly rather than through the screening API: this runs
 * on every captured frame while the officer is lining the traveller up, and routing a
 * preview loop through the pipeline that also does OCR and forensics would be wasteful.
 * A failure here is not worth surfacing - it only means the live hint is unavailable -
 * so the caller gets null rather than an exception.
 */
export async function checkFaceQuality(image, source = 'live') {
  const form = new FormData()
  form.append('image', image)
  form.append('source', source)
  try {
    const response = await fetch(`${FACE_SERVICE}/quality`, { method: 'POST', body: form })
    return response.ok ? await response.json() : null
  } catch {
    return null
  }
}
