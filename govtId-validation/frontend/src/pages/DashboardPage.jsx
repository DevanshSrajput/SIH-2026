import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { getStats } from '../api.js'
import Badge from '../components/Badge.jsx'
import { findingTitle } from '../findings.js'

const WINDOWS = [
  { hours: 1, label: 'Last hour' },
  { hours: 8, label: 'Last shift' },
  { hours: 24, label: 'Last 24 hours' },
  { hours: 168, label: 'Last 7 days' },
]

const VERDICT_ORDER = ['CLEAR', 'REVIEW', 'REJECT']

export default function DashboardPage() {
  const [windowHours, setWindowHours] = useState(24)
  const [stats, setStats] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    getStats(windowHours)
      .then((result) => {
        if (!cancelled) {
          setStats(result)
          setError(null)
        }
      })
      .catch((e) => !cancelled && setError(e.message))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [windowHours])

  const referralRate = Math.round((stats?.referralRate ?? 0) * 100)

  return (
    <>
      <header className="page-head">
        <div>
          <h1 className="page-title">Checkpoint dashboard</h1>
          <p className="page-subtitle">
            Throughput and referral pattern over the selected window. A spike in one finding
            code is often the first sign of a batch of forgeries circulating.
          </p>
        </div>

        {/* Segmented control rather than a select: the windows are few and switching
            between them is the main thing anyone does on this page. */}
        <div className="segmented" role="group" aria-label="Time window">
          {WINDOWS.map((w) => (
            <button
              key={w.hours}
              type="button"
              className={windowHours === w.hours ? 'is-active' : undefined}
              aria-pressed={windowHours === w.hours}
              onClick={() => setWindowHours(w.hours)}
            >
              {w.label}
            </button>
          ))}
        </div>
      </header>

      {error && (
        <div className="error" role="alert">
          {error}
        </div>
      )}

      {loading && <StatsSkeleton />}

      {!loading && stats && (
        <>
          <div className="grid-3" style={{ marginBottom: 16 }}>
            <div className="stat">
              <div className="stat-value">{stats.totalScreenings}</div>
              <div className="stat-label">Screened in window</div>
              <div className="stat-note">{stats.totalAllTime} since the system started</div>
            </div>
            <div className="stat">
              <div className="stat-value">
                {stats.referredForReview}
                <span className="stat-suffix"> / {referralRate}%</span>
              </div>
              <div className="stat-label">Referred to an officer</div>
              <div className="stat-note">{referralNote(referralRate, stats.totalScreenings)}</div>
            </div>
            <div className="stat">
              <div className="stat-value">
                {stats.medianProcessingMillis != null ? stats.medianProcessingMillis : '--'}
                <span className="stat-suffix"> ms</span>
              </div>
              <div className="stat-label">Median screening time</div>
              <div className="stat-note">
                {stats.slowestProcessingMillis != null
                  ? `slowest ${stats.slowestProcessingMillis} ms`
                  : 'no timings yet'}
              </div>
            </div>
          </div>

          <div className="grid-2">
            <div className="panel">
              <h2>Most frequent findings</h2>
              <FindingBars flags={stats.topFlags} />
            </div>

            <div>
              <div className="panel">
                <h2>Recommendations</h2>
                <VerdictSplit byVerdict={stats.byVerdict} />
              </div>

              <div className="panel">
                <h2>Highest risk cases</h2>
                {(stats.highestRiskCases ?? []).length === 0 ? (
                  <div className="empty">Nothing flagged in this window.</div>
                ) : (
                  <div className="rank-list">
                    {stats.highestRiskCases.map((row) => (
                      <Link
                        key={row.caseReference}
                        to={`/cases/${row.caseReference}`}
                        className="rank-row"
                      >
                        <span className="mono rank-ref">{row.caseReference}</span>
                        <span className={`score-meter verdict-${row.verdict}`}>
                          <span style={{ width: `${row.score}%` }} />
                        </span>
                        <span className="score-number">{row.score}</span>
                        <Badge value={row.verdict} />
                      </Link>
                    ))}
                  </div>
                )}
              </div>

              <div className="panel">
                <h2>Documents seen</h2>
                {Object.keys(stats.byDocumentType ?? {}).length === 0 ? (
                  <div className="empty">Nothing in this window.</div>
                ) : (
                  <div className="split-legend">
                    {Object.entries(stats.byDocumentType).map(([type, count]) => (
                      <div className="split-legend-row" key={type}>
                        <span>{type.replace(/_/g, ' ')}</span>
                        <span className="count">{count}</span>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </div>
        </>
      )}
    </>
  )
}

/**
 * Counts as bars rather than a column of numbers. The point of this panel is comparison -
 * whether one code is running away from the others - and a bar answers that without the
 * reader doing arithmetic.
 */
function FindingBars({ flags }) {
  const entries = Object.entries(flags ?? {})
  if (entries.length === 0) {
    return <div className="empty">No findings in this window.</div>
  }

  const max = Math.max(...entries.map(([, count]) => count))

  return (
    <div className="bar-list">
      {entries.map(([code, count]) => (
        <div className="bar-row" key={code}>
          <span className="bar-label">
            {findingTitle(code)} <span className="bar-code">{code}</span>
          </span>
          <span className="bar-count">{count}</span>
          <span className="bar-track">
            <span className="bar-fill" style={{ width: `${(count / max) * 100}%` }} />
          </span>
        </div>
      ))}
    </div>
  )
}

function VerdictSplit({ byVerdict }) {
  const counts = byVerdict ?? {}
  const total = Object.values(counts).reduce((sum, n) => sum + n, 0)

  if (total === 0) {
    return <div className="empty">No completed screenings in this window.</div>
  }

  return (
    <>
      <div className="split-bar" role="img" aria-label="Share of each recommendation">
        {VERDICT_ORDER.filter((v) => counts[v]).map((verdict) => (
          <span
            key={verdict}
            className={`split-seg verdict-${verdict}`}
            style={{ width: `${(counts[verdict] / total) * 100}%` }}
          />
        ))}
      </div>
      <div className="split-legend">
        {VERDICT_ORDER.filter((v) => counts[v] != null).map((verdict) => (
          <div className="split-legend-row" key={verdict}>
            <Badge value={verdict} />
            <span className="percent">{Math.round((counts[verdict] / total) * 100)}%</span>
            <span className="count">{counts[verdict]}</span>
          </div>
        ))}
      </div>
    </>
  )
}

/**
 * A referral rate this high usually means the system is crying wolf, not that a wave of
 * forgeries arrived. Officers who see REVIEW on honest documents stop reading the score.
 */
function referralNote(rate, total) {
  if (!total) return 'nothing screened yet'
  if (rate >= 40) return 'unusually high - check for false positives'
  if (rate === 0) return 'nothing referred'
  return 'within normal range'
}

function StatsSkeleton() {
  return (
    <>
      <div className="grid-3" style={{ marginBottom: 16 }}>
        {[0, 1, 2].map((i) => (
          <div className="stat" key={i}>
            <div className="skeleton" style={{ height: 30, width: '55%' }} />
            <div className="skeleton" style={{ width: '75%' }} />
          </div>
        ))}
      </div>
      <div className="panel">
        {[0, 1, 2, 3, 4].map((i) => (
          <div className="skeleton" key={i} style={{ width: `${90 - i * 12}%` }} />
        ))}
      </div>
    </>
  )
}
