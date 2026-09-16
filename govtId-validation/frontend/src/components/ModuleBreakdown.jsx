import { useState } from 'react'
import Badge from './Badge.jsx'
import { findingTitle, moduleLabel, moduleNote } from '../findings.js'

/**
 * The four screening modules, each expandable to what it actually measured.
 *
 * The timeline above this says whether a module ran. This says what it found and on what
 * evidence, which is the part that has to survive a challenge months later: a case
 * rejected on a tampering finding is only defensible if someone can still see the
 * measurement that produced it and the threshold it crossed.
 *
 * Collapsed by default, and the modules that raised findings open first. An officer at a
 * lane reads the verdict; an investigator reading the same case a year later opens
 * everything. The page has to serve both without making either scroll past the other's
 * view.
 */
export default function ModuleBreakdown({ results = [] }) {
  if (!results.length) {
    return <div className="empty">No module results were recorded for this case.</div>
  }

  return (
    <div className="module-breakdown">
      {results.map((result) => (
        <ModulePanel key={result.module} result={result} />
      ))}
    </div>
  )
}

function ModulePanel({ result }) {
  const findings = result.flags ?? []
  // Open on arrival when there is something to answer for.
  const [open, setOpen] = useState(findings.length > 0 || result.status === 'FAILED')

  const details = Object.entries(result.details ?? {}).filter(
    ([key]) => !HIDDEN_DETAIL_KEYS.has(key),
  )

  return (
    <section className={`module-panel status-${result.status}`}>
      <button
        type="button"
        className="module-panel-head"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <span className={`disclosure-caret ${open ? 'is-open' : ''}`} aria-hidden="true" />
        <span className="module-panel-title">{moduleLabel(result.module)}</span>
        <span className={`status-pill status-${result.status}`}>
          {STATUS_WORDING[result.status] ?? result.status}
        </span>
        {findings.length > 0 ? (
          <span className="finding-count">
            {findings.length} finding{findings.length === 1 ? '' : 's'}
          </span>
        ) : (
          result.status === 'COMPLETED' && <span className="finding-count is-clean">clean</span>
        )}
        {result.durationMillis != null && (
          <span className="module-duration">{result.durationMillis} ms</span>
        )}
      </button>

      {open && (
        <div className="module-panel-body">
          <p className="module-note">{moduleNote(result.module)}</p>

          {result.note && <p className="timeline-reason">{result.note}</p>}

          {findings.length > 0 && (
            <ul className="finding-cards">
              {findings.map((flag) => (
                <li key={flag.code} className={`finding-card severity-${flag.severity}`}>
                  <div className="finding-card-head">
                    <strong>{findingTitle(flag.code)}</strong>
                    <Badge value={flag.severity} />
                    <span className="mono finding-code">{flag.code}</span>
                  </div>
                  <p>{flag.message}</p>
                  {Object.keys(flag.evidence ?? {}).length > 0 && (
                    <dl className="evidence-grid">
                      {Object.entries(flag.evidence).map(([key, value]) => (
                        <div key={key}>
                          <dt>{humanise(key)}</dt>
                          <dd className="mono">{renderValue(value)}</dd>
                        </div>
                      ))}
                    </dl>
                  )}
                </li>
              ))}
            </ul>
          )}

          {findings.length === 0 && result.status === 'COMPLETED' && (
            <p className="module-clean">
              This module ran and raised nothing. That is a measurement, not an absence of
              one.
            </p>
          )}

          {details.length > 0 && (
            <details className="raw-details">
              <summary>What this module measured ({details.length} values)</summary>
              <dl className="evidence-grid">
                {details.map(([key, value]) => (
                  <div key={key}>
                    <dt>{humanise(key)}</dt>
                    <dd className="mono">{renderValue(value)}</dd>
                  </div>
                ))}
              </dl>
            </details>
          )}
        </div>
      )}
    </section>
  )
}

const STATUS_WORDING = {
  COMPLETED: 'Ran',
  SKIPPED: 'Not run',
  FAILED: 'Could not run',
}

/**
 * Detail keys already shown elsewhere on the panel, or too large to render inline.
 * The full payload is still on the case in the API response.
 */
const HIDDEN_DETAIL_KEYS = new Set([
  'engine',
  'reasons',
  'blockers',
  'summary',
  'quality',
  'liveness',
  'checks',
])

/** camelCase key to something readable, without a translation table per module. */
function humanise(key) {
  return key
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/[_-]+/g, ' ')
    .replace(/^./, (c) => c.toUpperCase())
}

function renderValue(value) {
  if (value === null || value === undefined) return '-'
  if (typeof value === 'boolean') return value ? 'yes' : 'no'
  if (typeof value === 'number') {
    // Long floating-point tails are noise; the magnitude is what is being read.
    return Number.isInteger(value) ? String(value) : value.toFixed(4)
  }
  if (Array.isArray(value)) return value.length ? value.join(', ') : '-'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}
