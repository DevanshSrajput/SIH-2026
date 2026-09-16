import { useEffect, useState } from 'react'
import {
  compareFaces,
  enrolFace,
  identifyFace,
  listEnrolments,
  withdrawEnrolment,
} from '../api.js'
import ErrorBoundary from '../components/ErrorBoundary.jsx'
import FaceCapture from '../components/FaceCapture.jsx'
import FileDrop from '../components/FileDrop.jsx'

const TABS = [
  { id: 'verify', label: 'Verify (1:1)', blurb: 'Is this traveller the person on this document?' },
  { id: 'identify', label: 'Identify (1:N)', blurb: 'Who is this traveller?' },
  { id: 'enrol', label: 'Enrol', blurb: 'Register a known traveller for future crossings.' },
]

export default function FaceAuthPage() {
  const [tab, setTab] = useState('verify')

  return (
    <>
      <header className="page-head">
        <div>
          <h1 className="page-title">Face authentication</h1>
          <p className="page-subtitle">
            {TABS.find((t) => t.id === tab)?.blurb}
          </p>
        </div>

        <div className="segmented" role="group" aria-label="Face authentication mode">
          {TABS.map((t) => (
            <button
              key={t.id}
              type="button"
              className={tab === t.id ? 'is-active' : undefined}
              aria-pressed={tab === t.id}
              onClick={() => setTab(t.id)}
            >
              {t.label}
            </button>
          ))}
        </div>
      </header>

      <ErrorBoundary title="The face authentication panel could not be displayed">
        {tab === 'verify' && <VerifyTab />}
        {tab === 'identify' && <IdentifyTab />}
        {tab === 'enrol' && <EnrolTab />}
      </ErrorBoundary>
    </>
  )
}

// ---------------------------------------------------------------------------
// 1:1 verification
// ---------------------------------------------------------------------------

function VerifyTab() {
  const [documentFile, setDocumentFile] = useState(null)
  const [liveFile, setLiveFile] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [result, setResult] = useState(null)

  async function submit(event) {
    event.preventDefault()
    if (!documentFile || !liveFile) {
      setError('Both a document image and a live capture are required.')
      return
    }

    setBusy(true)
    setError(null)
    setResult(null)
    try {
      setResult(await compareFaces({ document: documentFile, live: liveFile }))
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  function reset() {
    setDocumentFile(null)
    setLiveFile(null)
    setResult(null)
    setError(null)
  }

  return (
    <>
      {error && (
        <div className="error" role="alert">
          {error}
        </div>
      )}

      <div className="screen-grid">
        <form className="panel" onSubmit={submit}>
          <h2>Document portrait</h2>
          <FileDrop
            id="doc-face-image"
            label="Document image (required)"
            file={documentFile}
            onChange={setDocumentFile}
            hint="Upload the document image. The portrait is located and cropped automatically."
          />

          <h2>Live capture</h2>
          <FaceCapture onCapture={setLiveFile} label="Capture from camera" disabled={busy} />

          {liveFile && <CapturedPreview file={liveFile} onRemove={() => setLiveFile(null)} />}

          <div className="button-row form-actions">
            <button className="primary" type="submit" disabled={busy || !documentFile || !liveFile}>
              {busy ? 'Comparing...' : 'Compare faces'}
            </button>
            {(documentFile || liveFile || result) && (
              <button type="button" onClick={reset} disabled={busy}>
                Clear
              </button>
            )}
          </div>
        </form>

        <div className="results-column">
          {busy && (
            <div className="panel progress-panel">
              <div className="progress-bar" aria-hidden="true">
                <span />
              </div>
              <h2>Comparing faces</h2>
              <ul className="progress-stages">
                <li>Detecting faces</li>
                <li>Assessing image quality</li>
                <li>Checking liveness</li>
                <li>Computing similarity</li>
              </ul>
            </div>
          )}

          {!busy && result && <VerifyResult result={result} />}

          {!busy && !result && (
            <EmptyState
              title="No comparison yet"
              body="Upload a document image and capture a live photo. The decision, the reasons
                    behind it, and the image-quality assessment appear here."
            />
          )}
        </div>
      </div>
    </>
  )
}

/**
 * The verification result.
 *
 * The decision leads, not the number. A similarity percentage read on its own invites the
 * reading "82% sure it's them", which is not what a cosine similarity means and is exactly
 * the misreading that turns a marginal comparison into a waved-through impostor. So the
 * banner states the decision in words, the score is shown as a measurement against its
 * threshold, and an uncertain result says what to do next.
 */
function VerifyResult({ result }) {
  const decision = result.decision ?? 'UNCERTAIN'
  const { tone, heading } = DECISION_PRESENTATION[decision] ?? DECISION_PRESENTATION.UNCERTAIN
  const blockers = result.blockers ?? []
  const reasons = result.reasons ?? []

  return (
    <>
      <div className={`verdict-banner verdict-${tone}`}>
        <div className="verdict-body">
          <div className="verdict-head">
            <span className="verdict-label">{heading}</span>
            {result.confidence > 0 && (
              <span className="verdict-confidence">
                confidence {Math.round(result.confidence * 100)}%
              </span>
            )}
          </div>
          <div className="verdict-explanation">{result.summary}</div>
        </div>
      </div>

      {decision === 'UNCERTAIN' && (
        <div className="panel callout callout-review">
          <h2>This is not a result an officer can act on alone</h2>
          <p>
            The system has measured what it can and the measurement does not settle the
            question. Compare the portrait and the traveller yourself.
          </p>
          {blockers.length > 0 && (
            <>
              <h3>Why it could not decide</h3>
              <ul className="reason-list">
                {blockers.map((blocker) => (
                  <li key={blocker}>{blocker}</li>
                ))}
              </ul>
            </>
          )}
          {result.recaptureAdvice && (
            <p className="advice">
              <strong>Next step:</strong> {result.recaptureAdvice}
            </p>
          )}
        </div>
      )}

      <div className="panel">
        <h2>Measurement</h2>
        <SimilarityMeter
          similarity={result.similarity}
          thresholds={result.thresholds}
          decision={decision}
        />
        <dl className="detail-grid">
          <Detail label="Similarity (cosine)" value={result.similarity?.toFixed(3)} />
          <Detail
            label="Match threshold"
            value={result.thresholds?.match?.toFixed(2) ?? 'not reported'}
          />
          <Detail
            label="Mismatch threshold"
            value={result.thresholds?.mismatch?.toFixed(2) ?? 'not reported'}
          />
          <Detail label="Decision" value={decision} />
          <Detail label="Document face found" value={result.documentFaceFound ? 'Yes' : 'No'} />
          <Detail label="Live face found" value={result.liveFaceFound ? 'Yes' : 'No'} />
          <Detail label="Liveness score" value={`${Math.round(result.livenessScore * 100)}%`} />
          <Detail
            label="Liveness check"
            value={result.livenessLive ? 'Pass (likely live)' : 'Suspicious (possible spoof)'}
          />
          <Detail label="Engine" value={result.engine} />
        </dl>
      </div>

      {result.quality && <QualityPanel quality={result.quality} />}

      {reasons.length > 0 && (
        <div className="panel">
          <h2>How it reached this</h2>
          <ul className="reason-list">
            {reasons.map((reason) => (
              <li key={reason}>{reason}</li>
            ))}
          </ul>
        </div>
      )}

      <details className="panel">
        <summary>
          <h2>What the score means</h2>
        </summary>
        <p className="prose">
          The similarity is a raw cosine between two 128-dimensional SFace embeddings, not a
          percentage confidence. Two different people typically score near 0.0-0.25; the same
          person across a document scan and a live capture typically scores 0.40-0.75. The
          match threshold sits deliberately above SFace&apos;s published 0.363 break-even
          point, because at a checkpoint a false accept and a false referral do not cost the
          same thing.
        </p>
        <p className="prose">
          A positive identification additionally requires that both images clear the quality
          gate, that the capture passes the anti-spoofing check, and that only one face is in
          frame. Failing any of those returns UNCERTAIN however high the score is.
        </p>
      </details>
    </>
  )
}

const DECISION_PRESENTATION = {
  MATCH: { tone: 'CLEAR', heading: 'Faces match' },
  NO_MATCH: { tone: 'REJECT', heading: 'Faces do not match' },
  UNCERTAIN: { tone: 'REVIEW', heading: 'Cannot determine - officer check required' },
}

/** Where the score fell, relative to the two thresholds it was judged against. */
function SimilarityMeter({ similarity, thresholds, decision }) {
  const match = thresholds?.match ?? 0.46
  const mismatch = thresholds?.mismatch ?? 0.28
  const position = Math.max(0, Math.min(1, similarity ?? 0)) * 100

  return (
    <div className="similarity-meter">
      <div className="meter-track" role="img"
           aria-label={`Similarity ${similarity?.toFixed(3)} against a match threshold of ${match}`}>
        <span className="meter-zone meter-zone-reject" style={{ width: `${mismatch * 100}%` }} />
        <span
          className="meter-zone meter-zone-review"
          style={{ width: `${(match - mismatch) * 100}%` }}
        />
        <span className="meter-zone meter-zone-clear" style={{ width: `${(1 - match) * 100}%` }} />
        <span
          className={`meter-needle meter-needle-${decision}`}
          style={{ left: `${position}%` }}
        />
      </div>
      <div className="meter-scale">
        <span>0.0 no resemblance</span>
        <span>{mismatch.toFixed(2)}</span>
        <span>{match.toFixed(2)}</span>
        <span>1.0 identical</span>
      </div>
    </div>
  )
}

function QualityPanel({ quality }) {
  const sides = [
    ['Document portrait', quality.document],
    ['Live capture', quality.live],
  ].filter(([, value]) => value)

  if (sides.length === 0) return null

  return (
    <div className="panel">
      <h2>Image quality</h2>
      <p className="panel-note">
        A face embedding is only as good as the pixels behind it. An image below the gate is
        never used to confirm an identity.
      </p>
      {sides.map(([label, side]) => (
        <div className="quality-side" key={label}>
          <div className="quality-side-head">
            <strong>{label}</strong>
            <span className={side.usable ? 'pill pill-ok' : 'pill pill-bad'}>
              {side.usable ? 'usable' : 'below gate'}
            </span>
            <span className="quality-score">{Math.round((side.score ?? 0) * 100)}%</span>
          </div>
          <ul className="quality-checks">
            {(side.checks ?? []).map((check) => (
              <li key={check.name} className={check.passed ? 'is-ok' : 'is-bad'}>
                <span className="check-name">{check.name}</span>
                <span className="check-reason">{check.reason}</span>
              </li>
            ))}
          </ul>
          {!side.usable && side.advice && <p className="advice">{side.advice}</p>}
        </div>
      ))}
    </div>
  )
}

// ---------------------------------------------------------------------------
// 1:N identification
// ---------------------------------------------------------------------------

function IdentifyTab() {
  const [file, setFile] = useState(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [result, setResult] = useState(null)

  async function run() {
    if (!file) return
    setBusy(true)
    setError(null)
    setResult(null)
    try {
      setResult(await identifyFace(file))
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      {error && (
        <div className="error" role="alert">
          {error}
        </div>
      )}

      <div className="screen-grid">
        <div className="panel">
          <h2>Capture to identify</h2>
          <p className="panel-note">
            Searches every enrolled traveller. Held to a stricter bar than a 1:1 check: with a
            gallery, the nearest face is not automatically the right one.
          </p>
          <FaceCapture onCapture={setFile} label="Capture from camera" disabled={busy} />
          {file && <CapturedPreview file={file} onRemove={() => setFile(null)} />}

          <div className="button-row form-actions">
            <button className="primary" type="button" onClick={run} disabled={busy || !file}>
              {busy ? 'Searching...' : 'Identify'}
            </button>
          </div>
        </div>

        <div className="results-column">
          {busy && (
            <div className="panel progress-panel">
              <div className="progress-bar" aria-hidden="true">
                <span />
              </div>
              <h2>Searching the enrolled gallery</h2>
            </div>
          )}

          {!busy && result && <IdentifyResult result={result} />}

          {!busy && !result && (
            <EmptyState
              title="No search yet"
              body="Capture a face to search it against every enrolled traveller."
            />
          )}
        </div>
      </div>
    </>
  )
}

function IdentifyResult({ result }) {
  const tone = result.identified
    ? 'CLEAR'
    : result.decision === 'NO_MATCH'
      ? 'REJECT'
      : 'REVIEW'

  const heading = result.identified
    ? `Identified: ${result.displayName || result.subjectId}`
    : IDENTIFY_HEADINGS[result.decision] ?? 'Not identified'

  return (
    <>
      <div className={`verdict-banner verdict-${tone}`}>
        <div className="verdict-body">
          <div className="verdict-head">
            <span className="verdict-label">{heading}</span>
          </div>
          <div className="verdict-explanation">{result.reason}</div>
        </div>
      </div>

      {result.decision === 'AMBIGUOUS' && (
        <div className="panel callout callout-review">
          <h2>Two people scored too closely to separate</h2>
          <p>
            The gallery holds more than one face that resembles this capture. Naming either
            would be a guess. An officer must resolve it from the documents.
          </p>
        </div>
      )}

      {(result.candidates ?? []).length > 0 && (
        <div className="panel">
          <h2>Candidates</h2>
          <table className="data-table">
            <thead>
              <tr>
                <th>Subject</th>
                <th>Nationality</th>
                <th>Document</th>
                <th className="numeric">Similarity</th>
              </tr>
            </thead>
            <tbody>
              {result.candidates.map((candidate, index) => (
                <tr key={candidate.enrolmentId} className={index === 0 ? 'is-top' : undefined}>
                  <td>{candidate.displayName || candidate.subjectId}</td>
                  <td>{candidate.nationality || <span className="unset">unknown</span>}</td>
                  <td className="mono">
                    {candidate.documentNumberKey || <span className="unset">none</span>}
                  </td>
                  <td className="numeric mono">{candidate.similarity.toFixed(3)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="panel-note">
            Identification threshold {result.threshold?.toFixed(2)}; the top candidate must
            also beat the next different person by {result.requiredMargin?.toFixed(2)}.
            Gallery size: {result.gallerySize}.
          </p>
        </div>
      )}
    </>
  )
}

const IDENTIFY_HEADINGS = {
  NO_MATCH: 'Not enrolled',
  AMBIGUOUS: 'Ambiguous - officer check required',
  UNCERTAIN: 'Cannot determine - officer check required',
  EMPTY_GALLERY: 'No faces enrolled',
  NO_FACE: 'No face detected',
}

// ---------------------------------------------------------------------------
// Enrolment
// ---------------------------------------------------------------------------

const EMPTY_ENROLMENT = {
  subjectId: '',
  displayName: '',
  documentNumber: '',
  nationality: '',
  enrolledBy: '',
  notes: '',
}

function EnrolTab() {
  const [form, setForm] = useState(EMPTY_ENROLMENT)
  const [file, setFile] = useState(null)
  const [source, setSource] = useState('live')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [notice, setNotice] = useState(null)
  const [enrolments, setEnrolments] = useState(null)
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    let cancelled = false
    listEnrolments()
      .then((page) => !cancelled && setEnrolments(page.content))
      .catch((e) => !cancelled && setError(e.message))
    return () => {
      cancelled = true
    }
  }, [reloadKey])

  function set(field, value) {
    setForm((current) => ({ ...current, [field]: value }))
  }

  async function submit(event, acceptPoorQuality = false) {
    event.preventDefault()
    if (!file || !form.subjectId.trim()) {
      setError('A subject identifier and a reference image are both required.')
      return
    }

    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const saved = await enrolFace({ ...form, image: file, source, acceptPoorQuality })
      setNotice(
        `Enrolled ${saved.displayName || saved.subjectId} (quality ${Math.round(
          (saved.qualityScore ?? 0) * 100,
        )}%).`,
      )
      setForm(EMPTY_ENROLMENT)
      setFile(null)
      setReloadKey((k) => k + 1)
    } catch (e) {
      setError(e.message)
    } finally {
      setBusy(false)
    }
  }

  async function withdraw(entry) {
    try {
      await withdrawEnrolment(entry.id, form.enrolledBy || undefined)
      setReloadKey((k) => k + 1)
    } catch (e) {
      setError(e.message)
    }
  }

  // A rejected enrolment is almost always a quality rejection, and the officer needs the
  // override within reach - but only after being told why it was refused.
  const qualityRejected = error?.includes('not good enough to enrol')

  return (
    <>
      {error && (
        <div className="error" role="alert">
          {error}
          {qualityRejected && (
            <div className="button-row" style={{ marginTop: '10px' }}>
              <button type="button" onClick={(e) => submit(e, true)} disabled={busy}>
                Enrol anyway (recorded in the audit trail)
              </button>
            </div>
          )}
        </div>
      )}
      {notice && <div className="notice">{notice}</div>}

      <div className="screen-grid">
        <form className="panel" onSubmit={submit}>
          <h2>Reference image</h2>
          <p className="panel-note">
            A poor enrolment is not a one-off wrong answer. It becomes a permanent source of
            wrong answers for every future comparison against this person, so the quality
            gate applies here too.
          </p>

          <div className="field">
            <span className="label-text">Image source</span>
            <div className="segmented" role="group">
              <button
                type="button"
                className={source === 'live' ? 'is-active' : undefined}
                onClick={() => setSource('live')}
              >
                Live capture
              </button>
              <button
                type="button"
                className={source === 'document' ? 'is-active' : undefined}
                onClick={() => setSource('document')}
              >
                Document portrait
              </button>
            </div>
          </div>

          {source === 'live' ? (
            <FaceCapture onCapture={setFile} label="Capture from camera" disabled={busy} />
          ) : (
            <FileDrop
              id="enrol-image"
              label="Document image"
              file={file}
              onChange={setFile}
              hint="The portrait is located and cropped automatically."
            />
          )}

          {source === 'live' && file && (
            <CapturedPreview file={file} onRemove={() => setFile(null)} />
          )}

          <h2>Traveller</h2>
          <label className="field">
            <span className="label-text">Subject identifier (required)</span>
            <input
              value={form.subjectId}
              onChange={(e) => set('subjectId', e.target.value)}
              placeholder="e.g. the passport number, or an internal reference"
              required
            />
          </label>
          <label className="field">
            <span className="label-text">Display name</span>
            <input value={form.displayName} onChange={(e) => set('displayName', e.target.value)} />
          </label>
          <div className="field-row">
            <label className="field">
              <span className="label-text">Document number</span>
              <input
                value={form.documentNumber}
                onChange={(e) => set('documentNumber', e.target.value)}
              />
            </label>
            <label className="field">
              <span className="label-text">Nationality</span>
              <input
                value={form.nationality}
                onChange={(e) => set('nationality', e.target.value.toUpperCase())}
                maxLength={3}
                placeholder="IND"
              />
            </label>
          </div>
          <label className="field">
            <span className="label-text">Enrolling officer</span>
            <input value={form.enrolledBy} onChange={(e) => set('enrolledBy', e.target.value)} />
          </label>
          <label className="field">
            <span className="label-text">Notes</span>
            <textarea rows={2} value={form.notes} onChange={(e) => set('notes', e.target.value)} />
          </label>

          <div className="button-row form-actions">
            <button className="primary" type="submit" disabled={busy || !file}>
              {busy ? 'Enrolling...' : 'Enrol face'}
            </button>
          </div>
        </form>

        <div className="results-column">
          <div className="panel">
            <h2>Enrolled travellers</h2>
            {enrolments === null && <div className="skeleton-rows" aria-hidden="true" />}
            {enrolments?.length === 0 && (
              <p className="panel-note">Nobody is enrolled yet.</p>
            )}
            {enrolments?.length > 0 && (
              <table className="data-table">
                <thead>
                  <tr>
                    <th>Subject</th>
                    <th>Document</th>
                    <th className="numeric">Quality</th>
                    <th>Status</th>
                    <th />
                  </tr>
                </thead>
                <tbody>
                  {enrolments.map((entry) => (
                    <tr key={entry.id} className={entry.active ? undefined : 'is-inactive'}>
                      <td>{entry.displayName || entry.subjectId}</td>
                      <td className="mono">
                        {entry.documentNumberKey || <span className="unset">none</span>}
                      </td>
                      <td className="numeric">
                        {Math.round((entry.qualityScore ?? 0) * 100)}%
                        {!entry.qualityAccepted && (
                          <span className="pill pill-bad" title="Enrolled over the quality gate">
                            forced
                          </span>
                        )}
                      </td>
                      <td>{entry.active ? 'active' : 'withdrawn'}</td>
                      <td>
                        {entry.active && (
                          <button className="link-button" onClick={() => withdraw(entry)}>
                            Withdraw
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            <p className="panel-note">
              Withdrawn enrolments stop being matched but are never deleted - the record that
              a face was enrolled is itself part of the audit trail.
            </p>
          </div>
        </div>
      </div>
    </>
  )
}

// ---------------------------------------------------------------------------
// Shared
// ---------------------------------------------------------------------------

function CapturedPreview({ file, onRemove }) {
  const [url, setUrl] = useState(null)

  // Object URLs are revoked on replacement. Without this, every preview frame leaks a
  // blob for the lifetime of the page, which on a capture-heavy shift adds up.
  useEffect(() => {
    const objectUrl = URL.createObjectURL(file)
    setUrl(objectUrl)
    return () => URL.revokeObjectURL(objectUrl)
  }, [file])

  return (
    <div className="field">
      <span className="label-text">Captured image</span>
      <div className="dropzone has-file">
        <div className="dropzone-filled">
          {url && <img className="dropzone-thumb" src={url} alt="Live capture preview" />}
          <div className="dropzone-meta">
            <div className="dropzone-name">{file.name}</div>
            <button type="button" className="link-button" onClick={onRemove}>
              Remove
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

function EmptyState({ title, body }) {
  return (
    <div className="panel placeholder">
      <svg viewBox="0 0 24 24" width="40" height="40" aria-hidden="true">
        <path
          d="M12 12c2.5 0 4.5-2 4.5-4.5S14.5 3 12 3 7.5 5 7.5 7.5 9.5 12 12 12zm0 2c-3 0-9 1.5-9 4.5V21h18v-2.5c0-3-6-4.5-9-4.5z"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.4"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
      <h3>{title}</h3>
      <p>{body}</p>
    </div>
  )
}

function Detail({ label, value }) {
  return (
    <div className="detail-item">
      <dt>{label}</dt>
      <dd>{value || <span className="unset">not available</span>}</dd>
    </div>
  )
}
