import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { getAudit, getCase, imageUrl, recordDecision } from '../api.js'
import VerdictBanner from '../components/VerdictBanner.jsx'
import FindingList from '../components/FindingList.jsx'
import Badge from '../components/Badge.jsx'
import ModuleTimeline from '../components/ModuleTimeline.jsx'

const VISA_FIELDS = [
  'visaNumber',
  'visaType',
  'entryType',
  'stayDurationDays',
  'validFrom',
  'validUntil',
]

const DECISIONS = [
  { value: 'CLEAR', label: 'Clear' },
  { value: 'REVIEW', label: 'Refer for review' },
  { value: 'REJECT', label: 'Reject', className: 'danger' },
]

export default function CaseDetailPage() {
  const { reference } = useParams()

  const [screening, setScreening] = useState(null)
  const [audit, setAudit] = useState([])
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(true)
  const [notes, setNotes] = useState('')
  const [officerId, setOfficerId] = useState('')
  const [saving, setSaving] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const [caseData, auditData] = await Promise.all([
        getCase(reference),
        getAudit(reference).catch(() => []),
      ])
      setScreening(caseData)
      setAudit(auditData)
      setOfficerId(caseData.officerId || '')
      setNotes(caseData.officerNotes || '')
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }, [reference])

  useEffect(() => {
    load()
  }, [load])

  async function decide(decision) {
    setSaving(true)
    setError(null)
    try {
      await recordDecision(reference, { decision, officerId, notes })
      await load()
    } catch (e) {
      setError(e.message)
    } finally {
      setSaving(false)
    }
  }

  if (loading) return <CaseSkeleton />

  if (error && !screening) {
    return (
      <>
        <div className="error" role="alert">
          {error}
        </div>
        <div className="panel placeholder">
          <h3>Case {reference} could not be loaded</h3>
          <p>
            The reference may be wrong, or the service is unreachable. Nothing has been
            changed on the case either way.
          </p>
          <Link to="/cases">Back to all cases &rarr;</Link>
        </div>
      </>
    )
  }

  if (!screening) return null

  const extracted = screening.extracted
  const heatmap = findHeatmap(screening)
  const flagCount = screening.risk?.flags?.length ?? 0
  const hasVisaTerms = VISA_FIELDS.some((field) => extracted?.[field] != null)

  return (
    <>
      <header className="page-head">
        <div>
          <h1 className="page-title">Case {screening.caseReference}</h1>
          <p className="page-subtitle">
            {screening.documentType?.replace(/_/g, ' ')} presented at{' '}
            {screening.checkpointId || 'an unrecorded checkpoint'}
            {screening.laneId ? `, lane ${screening.laneId}` : ''} - screened in{' '}
            {screening.processingMillis} ms
          </p>
        </div>
        <span className="result-count">{formatInstant(screening.createdAt)}</span>
      </header>

      {error && (
        <div className="error" role="alert">
          {error}
        </div>
      )}

      <VerdictBanner risk={screening.risk} />

      <div className="grid-2">
        <div>
          <div className="panel">
            <h2>Extracted identity</h2>
            <table className="field-table">
              <tbody>
                <Row label="Surname" value={extracted?.surname} />
                <Row label="Given names" value={extracted?.givenNames} />
                <Row label="Document number" value={extracted?.documentNumber} mono />
                <Row label="Issuing state" value={extracted?.issuingState} />
                <Row label="Nationality" value={extracted?.nationality} />
                <Row label="Date of birth" value={extracted?.dateOfBirth} />
                <Row label="Sex" value={extracted?.sex} />
                <Row label="Date of expiry" value={extracted?.dateOfExpiry} />
                {/* Only for documents that carry visa terms. On a passport these fields are
                    not missing, they do not exist - and "not read" would wrongly suggest
                    the reader failed at something. */}
                {hasVisaTerms && (
                  <>
                    <Row label="Visa number" value={extracted?.visaNumber} mono />
                    <Row label="Visa type" value={extracted?.visaType} />
                    <Row label="Entry type" value={extracted?.entryType} />
                    <Row label="Permitted stay (days)" value={extracted?.stayDurationDays} />
                    <Row label="Valid from" value={extracted?.validFrom} />
                    <Row label="Valid until" value={extracted?.validUntil} />
                  </>
                )}
                <Row label="MRZ format" value={extracted?.mrz?.format} />
                <Row label="Read by" value={extracted?.engine} />
                <Row
                  label="OCR confidence"
                  value={
                    extracted?.ocrConfidence != null
                      ? `${Math.round(extracted.ocrConfidence * 100)}%`
                      : null
                  }
                />
              </tbody>
            </table>
          </div>

          <div className="panel">
            <h2>What ran</h2>
            <ModuleTimeline results={screening.moduleResults} />
          </div>

          <div className="panel">
            <h2>Officer determination</h2>
            {screening.officerDecision ? (
              <p>
                Recorded as <Badge value={screening.officerDecision} /> by{' '}
                {screening.officerId || 'an unnamed officer'} on{' '}
                {formatInstant(screening.decidedAt)}.
                {screening.officerNotes && <> Note: {screening.officerNotes}</>}
              </p>
            ) : (
              <p className="module-note">No officer decision recorded yet.</p>
            )}

            <div className="field">
              <label className="label-text" htmlFor="officer">
                Officer
              </label>
              <input
                id="officer"
                type="text"
                placeholder="off-114"
                value={officerId}
                onChange={(e) => setOfficerId(e.target.value)}
              />
            </div>

            <div className="field">
              <label className="label-text" htmlFor="officer-notes">
                Notes
              </label>
              <textarea
                id="officer-notes"
                value={notes}
                onChange={(e) => setNotes(e.target.value)}
                placeholder="What you checked, and what settled it."
              />
            </div>

            <div className="button-row form-actions">
              {DECISIONS.map((option) => (
                <button
                  key={option.value}
                  className={option.className}
                  disabled={saving}
                  onClick={() => decide(option.value)}
                >
                  {option.label}
                </button>
              ))}
            </div>
            <div className="hint">
              The system recommendation is never overwritten. Both views are kept, because a
              disagreement between them is what a later review needs to see.
            </div>
          </div>
        </div>

        <div>
          <div className="panel">
            <div className="panel-head">
              <h2>Findings</h2>
              {flagCount > 0 && <span className="count-pill">{flagCount}</span>}
            </div>
            <FindingList flags={screening.risk?.flags} />
          </div>

          <div className="panel">
            <h2>Evidence</h2>
            {screening.documentImageId ? (
              <img
                className="evidence-image"
                src={imageUrl(screening.caseReference, 'document')}
                alt="Presented document"
              />
            ) : (
              <div className="empty">No document image stored.</div>
            )}

            {screening.liveCaptureImageId && (
              <>
                <div className="label-text" style={{ marginTop: 16 }}>
                  Live capture
                </div>
                <img
                  className="evidence-image"
                  src={imageUrl(screening.caseReference, 'live')}
                  alt="Live capture"
                />
              </>
            )}

            {heatmap && (
              <>
                <div className="label-text" style={{ marginTop: 16 }}>
                  Error level analysis
                </div>
                <img className="heatmap" src={heatmap} alt="Error level analysis heatmap" />
                <div className="hint">
                  Bright areas compress differently from their surroundings. A localised
                  bright patch is where content was pasted in.
                </div>
              </>
            )}
          </div>

          <div className="panel">
            <div className="panel-head">
              <h2>Investigation trail</h2>
              {audit.length > 0 && <span className="count-pill">{audit.length}</span>}
            </div>
            {audit.length === 0 ? (
              <div className="empty">No audit events recorded.</div>
            ) : (
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>When</th>
                      <th>Action</th>
                      <th>Actor</th>
                    </tr>
                  </thead>
                  <tbody>
                    {audit.map((event) => (
                      <tr key={event.id}>
                        <td className="mono">{formatInstant(event.occurredAt)}</td>
                        <td>
                          {event.action}
                          {event.detail && <div className="module-note">{event.detail}</div>}
                        </td>
                        <td>{event.actor || <span className="unset">--</span>}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      </div>
    </>
  )
}

/**
 * Mirrors the shape of the loaded page rather than showing a centred spinner, so the
 * layout does not jump under the officer's cursor when the case arrives.
 */
function CaseSkeleton() {
  return (
    <>
      <div className="skeleton" style={{ height: 22, width: '38%' }} />
      <div className="skeleton" style={{ height: 14, width: '62%', marginBottom: 26 }} />
      <div className="panel">
        <div className="skeleton" style={{ height: 60, marginBottom: 0 }} />
      </div>
      <div className="grid-2">
        <div className="panel">
          {[0, 1, 2, 3, 4, 5, 6].map((i) => (
            <div className="skeleton" key={i} style={{ height: 16 }} />
          ))}
        </div>
        <div className="panel">
          {[0, 1, 2, 3].map((i) => (
            <div className="skeleton" key={i} style={{ height: 30 }} />
          ))}
        </div>
      </div>
    </>
  )
}

function Row({ label, value, mono }) {
  return (
    <tr>
      <td>{label}</td>
      <td className={mono ? 'mono' : undefined}>
        {value ?? <span className="unset">not read</span>}
      </td>
    </tr>
  )
}

/** Pulls the ELA heatmap out of whichever finding carried it. */
function findHeatmap(screening) {
  const flags = screening.risk?.flags ?? []
  for (const flag of flags) {
    if (flag.evidence?.heatmap) {
      return flag.evidence.heatmap
    }
  }
  return null
}

function formatInstant(value) {
  if (!value) return '--'
  try {
    return new Date(value).toLocaleString()
  } catch {
    return String(value)
  }
}
