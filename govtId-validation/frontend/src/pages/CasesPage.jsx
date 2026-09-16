import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { listCases } from '../api.js'
import Badge from '../components/Badge.jsx'

export default function CasesPage() {
  const [page, setPage] = useState(0)
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    listCases(page, 25)
      .then((result) => {
        if (!cancelled) {
          setData(result)
          setError(null)
        }
      })
      .catch((e) => !cancelled && setError(e.message))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [page])

  return (
    <>
      <header className="page-head">
        <div>
          <h1 className="page-title">Screened cases</h1>
          <p className="page-subtitle">
            Every document presented, most recent first. This history is also what makes repeat
            presentations and multiple identities visible.
          </p>
        </div>
        {data && !loading && (
          <span className="result-count">
            {data.totalElements} case{data.totalElements === 1 ? '' : 's'}
          </span>
        )}
      </header>

      {error && (
        <div className="error" role="alert">
          {error}
        </div>
      )}

      <div className="panel">
        {loading && (
          <div>
            {[0, 1, 2, 3, 4, 5].map((i) => (
              <div className="skeleton" key={i} style={{ height: 18 }} />
            ))}
          </div>
        )}

        {!loading && data?.content?.length === 0 && (
          <div className="placeholder">
            <h3>No documents screened yet</h3>
            <p>
              Cases appear here as soon as a document is screened. Every one is kept, because
              the history is what makes repeat presentations visible.
            </p>
            <Link to="/screen">Screen a document &rarr;</Link>
          </div>
        )}

        {!loading && data?.content?.length > 0 && (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Reference</th>
                  <th>Name</th>
                  <th>Document</th>
                  <th>Type</th>
                  <th>Risk</th>
                  <th>Recommendation</th>
                  <th>Officer</th>
                  <th>Findings</th>
                  <th>When</th>
                </tr>
              </thead>
              <tbody>
                {data.content.map((row) => (
                  <tr key={row.id}>
                    <td className="mono">
                      <Link className="row-link" to={`/cases/${row.caseReference}`}>
                        {row.caseReference}
                      </Link>
                    </td>
                    <td>{row.fullName || <span className="unset">not read</span>}</td>
                    <td className="mono">
                      {row.documentNumber || <span className="unset">--</span>}
                    </td>
                    <td>{row.documentType?.replace(/_/g, ' ')}</td>
                    <td>
                      {row.riskScore == null ? (
                        <span className="unset">--</span>
                      ) : (
                        <span className="score-cell">
                          <span className={`score-meter verdict-${row.verdict}`}>
                            <span style={{ width: `${row.riskScore}%` }} />
                          </span>
                          <span className="score-number">{row.riskScore}</span>
                        </span>
                      )}
                    </td>
                    <td>
                      <Badge value={row.verdict} />
                    </td>
                    <td>
                      <Badge value={row.officerDecision} fallback="pending" />
                    </td>
                    <td>{row.flagCount}</td>
                    <td className="module-note">{formatInstant(row.createdAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <div className="button-row">
        <button disabled={page === 0 || loading} onClick={() => setPage((p) => Math.max(0, p - 1))}>
          &larr; Previous
        </button>
        <button disabled={!data || data.last || loading} onClick={() => setPage((p) => p + 1)}>
          Next &rarr;
        </button>
        {data && (
          <span className="module-note" style={{ alignSelf: 'center' }}>
            Page {data.number + 1} of {Math.max(1, data.totalPages)}
          </span>
        )}
      </div>
    </>
  )
}

function formatInstant(value) {
  if (!value) return '--'
  try {
    return new Date(value).toLocaleString()
  } catch {
    return String(value)
  }
}
