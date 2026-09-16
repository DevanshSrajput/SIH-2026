import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  addWatchlistEntry,
  deactivateWatchlistEntry,
  importWatchlist,
  listWatchlist,
  reactivateWatchlistEntry,
  updateWatchlistEntry,
  watchlistExportUrl,
} from '../api.js'
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
  const [entries, setEntries] = useState(null)
  const [form, setForm] = useState(EMPTY_FORM)
  // Held outside the form: it identifies who is at the desk, and it is recorded against
  // deactivations just as much as additions. Clearing it when a form is submitted would
  // silently drop the actor from the next audit entry.
  const [officer, setOfficer] = useState('')
  const [error, setError] = useState(null)
  const [fetching, setFetching] = useState(false)
  const [saving, setSaving] = useState(false)
  const [showInactive, setShowInactive] = useState(false)
  // The id of the entry being edited, or null when the form is adding a new one.
  const [editingId, setEditingId] = useState(null)
  const [importReport, setImportReport] = useState(null)
  const [importing, setImporting] = useState(false)

  const load = useCallback(async () => {
    setFetching(true)
    try {
      const page = await listWatchlist()
      setEntries(page.content ?? [])
      setError(null)
    } catch (e) {
      setError(e.message)
    } finally {
      setFetching(false)
    }
  }, [])

  const loading = entries === null && fetching

  useEffect(() => {
    load()
  }, [load])

  const activeCount = useMemo(() => entries?.filter((e) => e.active).length ?? 0, [entries])
  const visible = showInactive ? (entries ?? []) : (entries ?? []).filter((e) => e.active)

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
      const body = {
        ...form,
        documentNumber: form.documentNumber || null,
        surname: form.surname || null,
        givenNames: form.givenNames || null,
        dateOfBirth: form.dateOfBirth || null,
        nationality: form.nationality || null,
        addedBy: officer || null,
      }
      if (editingId) {
        await updateWatchlistEntry(editingId, body)
      } else {
        await addWatchlistEntry(body)
      }
      setForm(EMPTY_FORM)
      setEditingId(null)
      await load()
    } catch (e) {
      setError(e.message)
    } finally {
      setSaving(false)
    }
  }

  /**
   * Loads an existing entry back into the form.
   *
   * The stored row keeps a combined display name but the lookup key is built from the
   * surname alone, so the two name parts are split back out on a best-effort basis: the
   * last word is taken as the surname. The officer can correct it, and they have to look
   * at it either way - an edit that silently reassembled the name wrong would produce an
   * entry that quietly stops matching.
   */
  function edit(entry) {
    const name = (entry.displayName ?? '').trim()
    const words = name ? name.split(/\s+/) : []
    setEditingId(entry.id)
    setForm({
      ...EMPTY_FORM,
      documentNumber: entry.documentNumberKey ?? '',
      surname: words.length ? words[words.length - 1] : '',
      givenNames: words.slice(0, -1).join(' '),
      dateOfBirth: entry.dateOfBirth ?? '',
      nationality: entry.nationality ?? '',
      listType: entry.listType ?? EMPTY_FORM.listType,
      severity: entry.severity ?? EMPTY_FORM.severity,
      reason: entry.reason ?? '',
      source: entry.source ?? '',
    })
    setError(null)
    window.scrollTo({ top: 0, behavior: 'smooth' })
  }

  function cancelEdit() {
    setEditingId(null)
    setForm(EMPTY_FORM)
  }

  async function reactivate(entry) {
    setError(null)
    try {
      await reactivateWatchlistEntry(entry.id, officer || undefined)
      await load()
    } catch (e) {
      setError(e.message)
    }
  }

  /**
   * Imports a JSON array of entries.
   *
   * Rows are applied independently server-side, so a feed carrying a few malformed
   * records still updates the rest of the list. The report names every rejected row -
   * an import that silently dropped entries would leave the officer believing the
   * watchlist covers people it does not.
   */
  async function runImport(event) {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return

    setImporting(true)
    setError(null)
    setImportReport(null)
    try {
      const parsed = JSON.parse(await file.text())
      if (!Array.isArray(parsed)) {
        throw new Error('The file must contain a JSON array of watchlist entries.')
      }
      // An export round-trips through this, and exported rows carry storage fields the
      // import endpoint does not accept. Map them back to the request shape.
      const entries = parsed.map((row) => ({
        documentNumber: row.documentNumber ?? row.documentNumberKey ?? null,
        surname: row.surname ?? lastWord(row.displayName),
        givenNames: row.givenNames ?? allButLastWord(row.displayName),
        dateOfBirth: row.dateOfBirth ?? null,
        nationality: row.nationality ?? null,
        listType: row.listType ?? null,
        severity: row.severity ?? null,
        reason: row.reason ?? null,
        source: row.source ?? null,
        addedBy: row.addedBy ?? officer ?? null,
      }))
      setImportReport(await importWatchlist(entries, officer || undefined))
      await load()
    } catch (e) {
      setError(e.message)
    } finally {
      setImporting(false)
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
        {!loading && !(error && (entries?.length ?? 0) === 0) && (
          <span className="result-count">
            {activeCount} active
            {(entries?.length ?? 0) > activeCount && ` / ${entries.length} total`}
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
          <h2>{editingId ? 'Edit entry' : 'Add an entry'}</h2>
          {editingId && (
            <p className="panel-note">
              Saving rebuilds this entry&apos;s lookup keys, so correcting a mistyped
              document number makes it start matching. Who raised it, and when, is kept.
            </p>
          )}

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
              {saving
                ? (editingId ? 'Saving...' : 'Adding...')
                : (editingId ? 'Save changes' : 'Add to watchlist')}
            </button>
            {editingId && (
              <button type="button" onClick={cancelEdit} disabled={saving}>
                Cancel
              </button>
            )}
          </div>
        </form>

        <div className="panel">
          <div className="panel-head">
            <h2>Current entries</h2>
            {(entries?.length ?? 0) > activeCount && (
              <button
                type="button"
                className="link-button"
                onClick={() => setShowInactive((shown) => !shown)}
              >
                {showInactive ? 'Hide deactivated' : `Show deactivated (${(entries?.length ?? 0) - activeCount})`}
              </button>
            )}
          </div>

          <div className="button-row toolbar-row">
            <label className="file-button">
              <input
                type="file"
                accept="application/json,.json"
                onChange={runImport}
                disabled={importing}
              />
              {importing ? 'Importing...' : 'Import JSON'}
            </label>
            <a className="file-button" href={watchlistExportUrl()} download="watchlist.json">
              Export JSON
            </a>
          </div>

          {importReport && (
            <div className={importReport.rejected > 0 ? 'notice notice-warn' : 'notice'}>
              <strong>
                Imported {importReport.imported} of {importReport.submitted} entries.
              </strong>
              {importReport.rejected > 0 && (
                <>
                  <p>
                    {importReport.rejected} row{importReport.rejected === 1 ? ' was' : 's were'}{' '}
                    rejected. The rest of the list was still updated.
                  </p>
                  <ul className="reason-list">
                    {importReport.rejections.slice(0, 10).map((rejection) => (
                      <li key={rejection}>{rejection}</li>
                    ))}
                  </ul>
                  {importReport.rejections.length > 10 && (
                    <p>and {importReport.rejections.length - 10} more.</p>
                  )}
                </>
              )}
              <button type="button" className="link-button" onClick={() => setImportReport(null)}>
                Dismiss
              </button>
            </div>
          )}

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
          {!loading && error && (entries?.length ?? 0) === 0 && (
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
              <h3>{(entries?.length ?? 0) === 0 ? 'The watchlist is empty' : 'No active entries'}</h3>
              <p>
                {(entries?.length ?? 0) === 0
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
                      <td className="row-actions">
                        <button className="link-button" onClick={() => edit(entry)}>
                          Edit
                        </button>
                        {entry.active ? (
                          <button className="link-button" onClick={() => deactivate(entry)}>
                            Deactivate
                          </button>
                        ) : (
                          <button className="link-button" onClick={() => reactivate(entry)}>
                            Reactivate
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

function lastWord(value) {
  const words = (value ?? '').trim().split(/\s+/).filter(Boolean)
  return words.length ? words[words.length - 1] : null
}

function allButLastWord(value) {
  const words = (value ?? '').trim().split(/\s+/).filter(Boolean)
  return words.length > 1 ? words.slice(0, -1).join(' ') : null
}
