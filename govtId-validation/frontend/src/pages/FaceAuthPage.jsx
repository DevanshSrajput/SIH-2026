import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { compareFaces } from '../api.js'
import FaceCapture from '../components/FaceCapture.jsx'
import FileDrop from '../components/FileDrop.jsx'

/**
 * Standalone face authentication page.
 *
 * Lets an officer compare a document portrait against a live capture without running
 * the full screening pipeline. Useful for quick identity verification, re-verification
 * after an inconclusive screening result, or enrollment.
 */
export default function FaceAuthPage() {
  const navigate = useNavigate()

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
      const authResult = await compareFaces({ document: documentFile, live: liveFile })
      setResult(authResult)
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
      <header className="page-head">
        <div>
          <h1 className="page-title">Face Authentication</h1>
          <p className="page-subtitle">
            Compare a document portrait against a live capture. The system checks whether
            the person presenting the document is the person it was issued to.
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
          <h2>Document portrait</h2>
          <FileDrop
            id="doc-face-image"
            label="Document image (required)"
            file={documentFile}
            onChange={setDocumentFile}
            hint="Upload the document image. The system will extract the portrait photo from it."
          />

          <h2>Live capture</h2>
          <FaceCapture
            onCapture={setLiveFile}
            label="Capture from camera"
            disabled={busy}
          />

          {liveFile && (
            <div className="field">
              <span className="label-text">Captured image</span>
              <div className="dropzone has-file">
                <div className="dropzone-filled">
                  <img
                    className="dropzone-thumb"
                    src={URL.createObjectURL(liveFile)}
                    alt="Live capture preview"
                  />
                  <div className="dropzone-meta">
                    <div className="dropzone-name">{liveFile.name}</div>
                    <button
                      type="button"
                      className="link-button"
                      onClick={() => setLiveFile(null)}
                    >
                      Remove
                    </button>
                  </div>
                </div>
              </div>
            </div>
          )}

          <div className="button-row form-actions">
            <button
              className="primary"
              type="submit"
              disabled={busy || !documentFile || !liveFile}
            >
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
                <li>Checking liveness</li>
                <li>Computing similarity</li>
              </ul>
            </div>
          )}

          {!busy && result && <FaceAuthResult result={result} />}

          {!busy && !result && (
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
              <h3>No comparison yet</h3>
              <p>
                Upload a document image and capture a live photo. The similarity score and
                liveness check results appear here.
              </p>
            </div>
          )}
        </div>
      </div>
    </>
  )
}

function FaceAuthResult({ result }) {
  const recommendation = result.recommendation
  const recClass =
    recommendation === 'MATCH'
      ? 'CLEAR'
      : recommendation === 'MISMATCH'
        ? 'REJECT'
        : 'REVIEW'

  const recLabel =
    recommendation === 'MATCH'
      ? 'Faces match'
      : recommendation === 'MISMATCH'
        ? 'Faces do not match'
        : 'Inconclusive'

  return (
    <>
      <div className={`verdict-banner verdict-${recClass}`}>
        <div className="verdict-body">
          <div className="verdict-head">
            <span className="verdict-label">{recLabel}</span>
          </div>
          <div className="verdict-explanation">
            Similarity: {(result.similarity * 100).toFixed(1)}%
            {result.livenessLive ? ' | Liveness: PASS' : ' | Liveness: SUSPICIOUS'}
          </div>
        </div>
      </div>

      <div className="panel">
        <h2>Face Comparison Details</h2>
        <dl className="detail-grid">
          <Detail label="Similarity" value={`${(result.similarity * 100).toFixed(1)}%`} />
          <Detail label="Recommendation" value={result.recommendation} />
          <Detail label="Document face found" value={result.documentFaceFound ? 'Yes' : 'No'} />
          <Detail label="Live face found" value={result.liveFaceFound ? 'Yes' : 'No'} />
          <Detail label="Liveness score" value={`${(result.livenessScore * 100).toFixed(1)}%`} />
          <Detail
            label="Liveness check"
            value={result.livenessLive ? 'Pass (likely live)' : 'Suspicious (possible photo)'}
          />
          <Detail label="Engine" value={result.engine} />
        </dl>
      </div>

      <div className="panel">
        <h2>How it works</h2>
        <p style={{ color: 'var(--muted)', fontSize: '13px', lineHeight: '1.6' }}>
          The system detects faces in both images using a DNN model, extracts 128-dimensional
          feature embeddings via the SFace recognition model, and computes cosine similarity.
          A liveness check examines texture variance and face characteristics to detect
          printed photograph spoofing. Similarity above 75% indicates a match; below 55%
          indicates a mismatch; between is inconclusive.
        </p>
      </div>
    </>
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
