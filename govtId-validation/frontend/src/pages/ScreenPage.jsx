import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { screenDocument } from '../api.js'
import VerdictBanner from '../components/VerdictBanner.jsx'
import FindingList from '../components/FindingList.jsx'
import FileDrop from '../components/FileDrop.jsx'
import ModuleTimeline from '../components/ModuleTimeline.jsx'

const DOCUMENT_TYPES = [
  'PASSPORT',
  'VISA',
  'NATIONAL_ID',
  'DRIVING_LICENCE',
  'PERMIT',
  'TRAVEL_AUTHORIZATION',
  'UNKNOWN',
]

const SAMPLE_MRZ =
  'P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<\nL898902C36UTO7408122F1204159ZE184226B<<<<<10'

export default function ScreenPage() {
  const navigate = useNavigate()

  const [documentFile, setDocumentFile] = useState(null)
  const [liveFile, setLiveFile] = useState(null)
  const [documentType, setDocumentType] = useState('PASSPORT')
  const [checkpointId, setCheckpointId] = useState('')
  const [laneId, setLaneId] = useState('')
  const [officerId, setOfficerId] = useState('')
  const [text, setText] = useState('')
  const [showAdvanced, setShowAdvanced] = useState(false)

  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [result, setResult] = useState(null)

  async function submit(event) {
    event.preventDefault()
    if (!documentFile) {
      setError('Select the document image before screening.')
      return
    }

    setBusy(true)
    setError(null)
    setResult(null)
    try {
      const screened = await screenDocument({
        document: documentFile,
        live: liveFile,
        documentType,
        checkpointId,
        laneId,
        officerId,
        text,
      })
      setResult(screened)
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  function reset() {
    setDocumentFile(null)
    setLiveFile(null)
    setText('')
    setResult(null)
    setError(null)
  }

  return (
    <>
      <header className="page-head">
        <div>
          <h1 className="page-title">Screen a document</h1>
          <p className="page-subtitle">
            Upload the presented document. Every module runs, and a recommendation comes back
            with the reasoning behind it.
          </p>
        </div>
      </header>

      {error && (
        <div className="error" role="alert">
          {error}
        </div>
      )}

      <div className="screen-grid">
        <form className="panel" onSubmit={submit}>
          <h2>Presented document</h2>

          <FileDrop
            id="document-image"
            label="Document image (required)"
            file={documentFile}
            onChange={setDocumentFile}
          />

          <div className="field">
            <label className="label-text" htmlFor="document-type">
              Document type
            </label>
            <select
              id="document-type"
              value={documentType}
              onChange={(e) => setDocumentType(e.target.value)}
            >
              {DOCUMENT_TYPES.map((type) => (
                <option key={type} value={type}>
                  {type.replace(/_/g, ' ')}
                </option>
              ))}
            </select>
            <div className="hint">
              A hint only - the modules report what they actually find, not what was selected.
              Choosing the wrong type judges the document against rules it was never meant to
              satisfy.
            </div>
          </div>

          <FileDrop
            id="live-capture"
            label="Live capture (optional)"
            file={liveFile}
            onChange={setLiveFile}
            hint="Enables face verification. Without it, that module reports it did not run rather than guessing."
          />

          <button
            type="button"
            className="disclosure"
            aria-expanded={showAdvanced}
            onClick={() => setShowAdvanced((open) => !open)}
          >
            <span className={`disclosure-caret${showAdvanced ? ' is-open' : ''}`} aria-hidden="true">
              &rsaquo;
            </span>
            Chip read and lane details
            <span className="disclosure-note">optional</span>
          </button>

          {showAdvanced && (
            <div className="disclosure-panel">
              <div className="field">
                <label className="label-text" htmlFor="chip-text">
                  Chip read or keyed MRZ
                </label>
                <textarea
                  id="chip-text"
                  value={text}
                  onChange={(e) => setText(e.target.value)}
                  placeholder={SAMPLE_MRZ}
                  spellCheck={false}
                />
                <div className="hint">
                  Trusted over pixel OCR when supplied - it is the exact text the chip or the
                  printed page carries.
                </div>
              </div>

              <div className="grid-3">
                <div className="field">
                  <label className="label-text" htmlFor="checkpoint">
                    Checkpoint
                  </label>
                  <input
                    id="checkpoint"
                    type="text"
                    placeholder="LHR-T5"
                    value={checkpointId}
                    onChange={(e) => setCheckpointId(e.target.value)}
                  />
                </div>
                <div className="field">
                  <label className="label-text" htmlFor="lane">
                    Lane
                  </label>
                  <input
                    id="lane"
                    type="text"
                    placeholder="LANE-04"
                    value={laneId}
                    onChange={(e) => setLaneId(e.target.value)}
                  />
                </div>
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
              </div>
            </div>
          )}

          <div className="button-row form-actions">
            <button className="primary" type="submit" disabled={busy || !documentFile}>
              {busy ? 'Screening...' : 'Screen document'}
            </button>
            {(documentFile || result) && (
              <button type="button" onClick={reset} disabled={busy}>
                Clear
              </button>
            )}
          </div>
        </form>

        <div className="results-column">
          {busy && <ScreeningProgress />}

          {!busy && result && (
            <>
              <VerdictBanner risk={result.risk} />

              <div className="panel">
                <div className="panel-head">
                  <h2>Case {result.caseReference}</h2>
                  <button className="link-button" onClick={() => navigate(`/cases/${result.caseReference}`)}>
                    Open full case &rarr;
                  </button>
                </div>

                <dl className="detail-grid">
                  <Detail label="Name" value={fullName(result.extracted)} />
                  <Detail label="Document number" value={result.extracted?.documentNumber} mono />
                  <Detail label="Nationality" value={result.extracted?.nationality} />
                  <Detail label="Date of birth" value={result.extracted?.dateOfBirth} />
                  <Detail label="Expires" value={result.extracted?.dateOfExpiry} />
                  <Detail
                    label="Read by"
                    value={result.extracted?.engine}
                    hint={`${result.processingMillis} ms`}
                  />
                </dl>
              </div>

              <div className="panel">
                <h2>What ran</h2>
                <ModuleTimeline results={result.moduleResults} />
              </div>

              <div className="panel">
                <div className="panel-head">
                  <h2>Findings</h2>
                  {result.risk?.flags?.length > 0 && (
                    <span className="count-pill">{result.risk.flags.length}</span>
                  )}
                </div>
                <FindingList flags={result.risk?.flags} />
              </div>
            </>
          )}

          {!busy && !result && (
            <div className="panel placeholder">
              <svg viewBox="0 0 24 24" width="40" height="40" aria-hidden="true">
                <path
                  d="M4 5.5A1.5 1.5 0 0 1 5.5 4h13A1.5 1.5 0 0 1 20 5.5v13a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 18.5v-13Z M8 9h5M8 12.5h8M8 16h6"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.4"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
              <h3>No document screened yet</h3>
              <p>
                Add the presented document on the left. The recommendation, the modules that
                ran, and every finding behind the score appear here.
              </p>
            </div>
          )}
        </div>
      </div>
    </>
  )
}

/** Named stages rather than a bare spinner, so a slow screening still says what it is doing. */
function ScreeningProgress() {
  const stages = [
    'Reading the document',
    'Checking the rules',
    'Looking for tampering',
    'Verifying the bearer',
    'Watchlist and history',
  ]
  return (
    <div className="panel progress-panel">
      <div className="progress-bar" aria-hidden="true">
        <span />
      </div>
      <h2>Screening in progress</h2>
      <ul className="progress-stages">
        {stages.map((stage, i) => (
          <li key={stage} style={{ animationDelay: `${i * 120}ms` }}>
            {stage}
          </li>
        ))}
      </ul>
    </div>
  )
}

function Detail({ label, value, mono, hint }) {
  return (
    <div className="detail-item">
      <dt>{label}</dt>
      <dd className={mono ? 'mono' : undefined}>
        {value || <span className="unset">not read</span>}
        {hint && value && <span className="detail-hint">{hint}</span>}
      </dd>
    </div>
  )
}

function fullName(extracted) {
  if (!extracted) return null
  return [extracted.givenNames, extracted.surname].filter(Boolean).join(' ')
}
