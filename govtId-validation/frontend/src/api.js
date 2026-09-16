/**
 * Thin client over the screening API.
 *
 * Errors are surfaced with the server's own message rather than a generic one: an
 * officer needs to know whether a screening failed because the image was too large or
 * because the service is down, and those demand different responses at the desk.
 */

const BASE = '/api'

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

export function listCases(page = 0, size = 25) {
  return fetch(`${BASE}/screenings?page=${page}&size=${size}`).then(handle)
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
  return fetch(`${BASE}/watchlist?page=${page}&size=${size}`).then(handle)
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
