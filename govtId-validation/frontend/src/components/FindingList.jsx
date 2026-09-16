import Badge from './Badge.jsx'
import { bySeverity, evidenceLabel, findingPlain, findingTitle } from '../findings.js'

/**
 * Findings, most severe first, each with the evidence that produced it.
 *
 * Three layers, in the order an officer needs them: what was found in plain words, why
 * that matters, and the actual values the rule compared. The machine code stays on the
 * row because it is the handle the case is argued with later - but it is no longer the
 * first thing the eye lands on.
 */
export default function FindingList({ flags }) {
  const ordered = bySeverity(flags)

  if (!ordered.length) {
    return (
      <div className="empty empty-good">
        <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
          <path
            d="M5 12.5l4.5 4.5L19 7.5"
            fill="none"
            stroke="currentColor"
            strokeWidth="2.2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
        No findings were raised.
      </div>
    )
  }

  return (
    <div className="finding-list">
      {ordered.map((flag, index) => {
        const plain = findingPlain(flag.code)
        const evidence = Object.entries(flag.evidence ?? {}).filter(([key]) => key !== 'heatmap')

        return (
          <div key={`${flag.code}-${index}`} className={`finding sev-${flag.severity}`}>
            <div className="finding-head">
              <Badge value={flag.severity} />
              <span className="finding-title">{findingTitle(flag.code)}</span>
              <code className="finding-code">{flag.code}</code>
            </div>

            {flag.message && <div className="finding-message">{flag.message}</div>}
            {plain && <div className="finding-plain">{plain}</div>}

            {evidence.length > 0 && (
              <dl className="evidence">
                {evidence.map(([key, value]) => (
                  <div className="evidence-item" key={key}>
                    <dt>{evidenceLabel(key)}</dt>
                    <dd>{formatValue(value)}</dd>
                  </div>
                ))}
              </dl>
            )}
          </div>
        )
      })}
    </div>
  )
}

function formatValue(value) {
  if (value === null || value === undefined) return 'null'
  if (Array.isArray(value)) return value.join(', ')
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}
