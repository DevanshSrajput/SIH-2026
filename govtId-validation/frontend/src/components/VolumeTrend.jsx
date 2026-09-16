import { useState } from 'react'

/**
 * Screening volume over the selected window, split by recommendation.
 *
 * A stacked column rather than a line: the buckets are discrete periods and the question
 * is composition within each one - how much of this hour was referred - which a line
 * cannot answer.
 *
 * The three recommendation colours are the console's status tokens, and green against
 * amber measures ΔE 5.1 under protanopia: a red-green reader cannot separate CLEAR from
 * REVIEW by hue. So hue is never the only encoding here. The stack order is fixed
 * (clear, review, reject, bottom up), REVIEW carries a diagonal hatch and REJECT a
 * cross-hatch, every segment is separated by a 2px surface gap, the legend is always
 * present, and a table view carries the same numbers for anyone the graphic fails.
 */
export default function VolumeTrend({ series }) {
  const [showTable, setShowTable] = useState(false)
  const [hovered, setHovered] = useState(null)

  const points = series ?? []
  if (points.length === 0) {
    return <div className="empty">No screenings in this window.</div>
  }

  const peak = Math.max(1, ...points.map((p) => p.total))

  return (
    <div className="volume-trend">
      <div className="chart-toolbar">
        <ul className="chart-legend">
          {SEGMENTS.map((segment) => (
            <li key={segment.key}>
              <span
                className={`legend-swatch verdict-${segment.verdict} texture-${segment.texture}`}
                aria-hidden="true"
              />
              {segment.label}
            </li>
          ))}
        </ul>
        <button type="button" className="link-button" onClick={() => setShowTable((v) => !v)}>
          {showTable ? 'Show chart' : 'Show as table'}
        </button>
      </div>

      {showTable ? (
        <table className="data-table">
          <thead>
            <tr>
              <th>Period</th>
              <th className="numeric">Cleared</th>
              <th className="numeric">Referred</th>
              <th className="numeric">Rejected</th>
              <th className="numeric">Total</th>
            </tr>
          </thead>
          <tbody>
            {points.map((point) => (
              <tr key={point.from}>
                <td>{formatBucket(point)}</td>
                <td className="numeric">{point.clear}</td>
                <td className="numeric">{point.review}</td>
                <td className="numeric">{point.reject}</td>
                <td className="numeric">{point.total}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : (
        <>
          <div className="trend-plot" role="img"
               aria-label={`Screening volume across ${points.length} periods, peak ${peak}`}>
            {points.map((point) => {
              const isHovered = hovered === point.from
              return (
                <div
                  className="trend-column"
                  key={point.from}
                  onMouseEnter={() => setHovered(point.from)}
                  onMouseLeave={() => setHovered(null)}
                  onFocus={() => setHovered(point.from)}
                  onBlur={() => setHovered(null)}
                  tabIndex={0}
                >
                  {isHovered && (
                    <div className="trend-tooltip" role="status">
                      <strong>{formatBucket(point)}</strong>
                      <span>{point.total} screened</span>
                      {SEGMENTS.filter((s) => point[s.key] > 0).map((s) => (
                        <span key={s.key}>
                          {s.label}: {point[s.key]}
                        </span>
                      ))}
                    </div>
                  )}

                  <div className="trend-stack">
                    {/* Bottom-up, so the order is the same in every column. */}
                    {[...SEGMENTS].reverse().map((segment) =>
                      point[segment.key] > 0 ? (
                        <span
                          key={segment.key}
                          className={`trend-seg verdict-${segment.verdict} texture-${segment.texture}`}
                          style={{ height: `${(point[segment.key] / peak) * 100}%` }}
                        />
                      ) : null,
                    )}
                  </div>

                  {/* Only the peak is labelled. A number on every column is noise. */}
                  <span className="trend-value">
                    {point.total === peak && point.total > 0 ? point.total : ''}
                  </span>
                </div>
              )
            })}
          </div>

          <div className="trend-axis">
            <span>{formatBucket(points[0])}</span>
            <span>{formatBucket(points[points.length - 1])}</span>
          </div>
        </>
      )}
    </div>
  )
}

/** Fixed order. Colour follows the recommendation, never its rank in this window. */
const SEGMENTS = [
  { key: 'clear', verdict: 'CLEAR', label: 'Cleared', texture: 'solid' },
  { key: 'review', verdict: 'REVIEW', label: 'Referred', texture: 'diagonal' },
  { key: 'reject', verdict: 'REJECT', label: 'Rejected', texture: 'cross' },
]

function formatBucket(point) {
  if (!point?.from) return ''
  const from = new Date(point.from)
  return from.toLocaleString(undefined, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}
