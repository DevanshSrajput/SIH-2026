import { useCallback, useEffect, useMemo, useState } from 'react'
import { addWatchlistEntry, deactivateWatchlistEntry, listWatchlist } from '../api.js'
import Badge from '../components/Badge.jsx'

/*
 * Each list says what it actually means. "ENTRY_BAN" and "LOCAL_INTEREST" are not
 * interchangeable to the screening engine, and an officer picking one under time pressure
 * should not have to remember which is which.
 */
const LIST_TYPES = [
  {
    value: 'STOLEN_DOCUMENT',
    note: 'Reported lost or stolen by the issuing authority.',
  },
  {
    value: 'REVOKED_DOCUMENT',
    note: 'Revoked or cancelled by the issuing authority; the document itself is no longer valid.',
  },
  { value: 'ENTRY_BAN', note: 'The person is barred from entry.' },
  { value: 'WANTED', note: 'The person is wanted by a law-enforcement agency.' },
  { value: 'VISA_OVERSTAY', note: 'The person has previously overstayed a visa.' },
  { value: 'LOCAL_INTEREST', note: 'Raised locally by checkpoint intelligence.' },
]

const SEVERITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW']

const EMPTY_FORM = {
  documentNumber: '',
  surname: '',
  givenNames: '',
  dateOfBirth: '',
  nationality: '',
  listType: 'STOLEN_DOCUMENT',
  severity: 'CRITICAL',
  reason: '',
  source: '',
}

export default function WatchlistPage() {
  const [entries, setEntries] = useState([])
  const [form, setForm] = useState(EMPTY_FORM)
  // Held outside the form: it identifies who is at the desk, and it is recorded against
  // deactivations just as much as additions. Clearing it when a form is submitted would
  // silently drop the actor from the next audit entry.
  const [officer, setOfficer] = useState('')
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [showInactive, setShowInactive] = useState(false)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const page = await listWatchlist()
      setEntries(page.content ?? [])
      setError(null)
    } catch (e) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const activeCount = useMemo(() => entries.filter((e) => e.active).length, [entries])
  const visible = showInactive ? entries : entries.filter((e) => e.active)

  // The backend matches on a document number, or on a name together with a date of birth.
  // Saying so before the request is refused beats a server error after the typing is done.
  const matchable =
    form.documentNumber.trim() !== '' ||
    (form.surname.trim() !== '' && form.dateOfBirth.trim() !== '')

  function update(field, value) {
    setForm((current) => ({ ...current, [field]: value }))
  }

  async function submit(event) {
    event.preventDefault()
    setSaving(true)
    setError(null)
    try {
      await addWatchlistEntry({
        ...form,
        documentNumber: form.documentNumber || null,
        surname: form.surname || null,
        givenNames: form.givenNames || null,
        dateOfBirth: form.dateOfBirth || null,
        nationality: form.nationality || null,
        addedBy: officer || null,
      })
      setForm(EMPTY_FORM)
      await load()
    } catch (e) {
      setError(e.message)
    } finally {
      setSaving(false)
    }
  }

  async function deactivate(entry) {
    setError(null)
    try {
      await deactivateWatchlistEntry(entry.id, officer || undefined)
      await load()
    } catch (e) {
      setError(e.message)
    }
  }

  const listNote = LIST_TYPES.find((t) => t.value === form.listType)?.note

  return (
    <>
      <header className="page-head">
        <div>
          <h1 className="page-title">Watchlist</h1>
          <p className="page-subtitle">
            Stolen, revoked and flagged documents and identities. Every presentation is
            matched against these, so an entry added here changes the next screening.
          </p>
        </div>
        {!loading && !(error && entries.length === 0) && (
          <span className="result-count">
            {activeCount} active
            {entries.length > activeCount && ` / ${entries.length} total`}
          </span>
        )}
      </header>

      {error && (
        <div className="error" role="alert">
          {error}
        </div>
      )}

      <div className="grid-2">
        <form className="panel" onSubmit={submit}>
          <h2>Add an entry</h2>

          <div className="field">
            <label className="label-text" htmlFor="wl-document">
              Document number
            </label>
            <input
              id="wl-document"
              type="text"
              value={form.documentNumber}
              onChange={(e) => update('documentNumber', e.target.value)}
            />
          </div>

          <div className="grid-2">
            <div className="field">
              <label className="label-text" htmlFor="wl-surname">
                Surname
              </label>
              <input
                id="wl-surname"
                type="text"
                value={form.surname}
                onChange={(e) => update('surname', e.target.value)}
              />
            </div>
            <div className="field">
              <label className="label-text" htmlFor="wl-given">
                Given names
              </label>
              <input
                id="wl-given"
                type="text"
                value={form.givenNames}
                onChange={(e) => update('givenNames', e.target.value)}
              />
            </div>
          </div>

          <div className="grid-2">
            <div className="field">
              <label className="label-text" htmlFor="wl-dob">
                Date of birth
              </label>
              <input
                id="wl-dob"
                type="date"
                value={form.dateOfBirth}
                onChange={(e) => update('dateOfBirth', e.target.value)}
              />
            </div>
            <div className="field">
              <label className="label-text" htmlFor="wl-nationality">
                Nationality (alpha-3)
              </label>
              <input
                id="wl-nationality"
                type="text"
                maxLength={3}
                placeholder="UTO"
                value={form.nationality}
                onChange={(e) => update('nationality', e.target.value.toUpperCase())}
              />
            </div>
          </div>

          <div className={`quality quality-${matchable ? 'good' : 'warn'}`}>
            {matchable
              ? 'Enough to match a presented document against.'
              : 'Provide a document number, or a surname together with a date of birth. A name on its own is too common to match on safely.'}
          </div>

          <div className="grid-2">
            <div className="field">
              <label className="label-text" htmlFor="wl-list">
                List
              </label>
              <select
                id="wl-list"
                value={form.listType}
                onChange={(e) => update('listType', e.target.value)}
              >
                {LIST_TYPES.map((type) => (
                  <option key={type.value} value={type.value}>
                    {type.value.replace(/_/g, ' ')}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label className="label-text" htmlFor="wl-severity">
                Severity
              </label>
              <select
                id="wl-severity"
                value={form.severity}
                onChange={(e) => update('severity', e.target.value)}
              >
                {SEVERITIES.map((severity) => (
                  <option key={severity} value={severity}>
                    {severity}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {listNote && <div className="hint">{listNote}</div>}

          <div className="field">
            <label className="label-text" htmlFor="wl-reason">
              Reason
            </label>
            <input
              id="wl-reason"
              type="text"
              value={form.reason}
              onChange={(e) => update('reason', e.target.value)}
            />
            <div className="hint">
              Shown to whoever meets this hit at the desk. Write what they need to do, not
              just the fact of the listing.
            </div>
          </div>

          <div className="grid-2">
            <div className="field">
              <label className="label-text" htmlFor="wl-source">
                Source
              </label>
              <input
                id="wl-source"
                type="text"
                placeholder="Interpol SLTD"
                value={form.source}
                onChange={(e) => update('source', e.target.value)}
              />
            </div>
            <div className="field">
              <label className="label-text" htmlFor="wl-officer">
                Officer
              </label>
              <input
                id="wl-officer"
                type="text"
                placeholder="off-114"
                value={officer}
                onChange={(e) => setOfficer(e.target.value)}
              />
            </div>
          </div>

          <div className="hint">
            The officer is recorded against anything added or deactivated on this page.
          </div>

          <div className="button-row form-actions">
            <button className="primary" type="submit" disabled={saving || !matchable}>
              {saving ? 'Adding...' : 'Add to watchlist'}
            </button>
          </div>
        </form>

        <div className="panel">
          <div className="panel-head">
            <h2>Current entries</h2>
            {entries.length > activeCount && (
              <button
                type="button"
                className="link-button"
                onClick={() => setShowInactive((shown) => !shown)}
              >
                {showInactive ? 'Hide deactivated' : `Show deactivated (${entries.length - activeCount})`}
              </button>
            )}
          </div>

          {loading && (
            <div>
              {[0, 1, 2, 3, 4].map((i) => (
                <div className="skeleton" key={i} style={{ height: 20 }} />
              ))}
            </div>
          )}

          {/* An empty list and a list that could not be read are opposite facts, and only
              one of them means nothing is being watched for. Never render the reassuring
              one when the request failed. */}
          {!loading && error && entries.length === 0 && (
            <div className="placeholder">
              <h3>The watchlist could not be read</h3>
              <p>
                This is not the same as the watchlist being empty. Until it loads, assume
                entries exist that are not shown here.
              </p>
              <button type="button" className="link-button" onClick={load}>
                Try again
              </button>
            </div>
          )}

          {!loading && !error && visible.length === 0 && (
            <div className="placeholder">
              <h3>{entries.length === 0 ? 'The watchlist is empty' : 'No active entries'}</h3>
              <p>
                {entries.length === 0
                  ? 'Nothing is being matched against yet. Add a stolen or revoked document on the left and the next screening will pick it up.'
                  : 'Every entry here has been deactivated. Past cases still show why they were rejected.'}
              </p>
            </div>
          )}

          {!loading && visible.length > 0 && (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Document</th>
                    <th>Identity</th>
                    <th>List</th>
                    <th>Severity</th>
                    <th>Status</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {visible.map((entry) => (
                    <tr key={entry.id}>
                      <td className="mono">
                        {entry.documentNumberKey || <span className="unset">--</span>}
                      </td>
                      <td>
                        {entry.displayName || <span className="unset">document only</span>}
                        {(entry.dateOfBirth || entry.nationality) && (
                          <div className="module-note">
                            {[entry.dateOfBirth, entry.nationality].filter(Boolean).join(' - ')}
                          </div>
                        )}
                        {entry.reason && <div className="module-note">{entry.reason}</div>}
                      </td>
                      <td>
                        {entry.listType?.replace(/_/g, ' ')}
                        {entry.source && <div className="module-note">{entry.source}</div>}
                      </td>
                      <td>
                        <Badge value={entry.severity} />
                      </td>
                      <td>
                        <span
                          className={`status-pill status-${entry.active ? 'active' : 'inactive'}`}
                        >
                          {entry.active ? 'Active' : 'Inactive'}
                        </span>
                      </td>
                      <td>
                        {entry.active && (
                          <button className="link-button" onClick={() => deactivate(entry)}>
                            Deactivate
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          <div className="hint" style={{ marginTop: 12 }}>
            Entries are deactivated, never deleted. Removing a row outright would erase the
            reason any past case was rejected.
          </div>
        </div>
      </div>
    </>
  )
}
