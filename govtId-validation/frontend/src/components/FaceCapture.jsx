import { useCallback, useEffect, useRef, useState } from 'react'
import { checkFaceQuality } from '../api.js'

/**
 * Webcam face capture with live quality feedback.
 *
 * The feedback loop is the point. A blurred or badly lit capture does not produce a wrong
 * answer downstream - the pipeline refuses to decide on it - but the officer only finds
 * that out after submitting, and by then the traveller has moved. Sampling the frame every
 * second and saying "too dark, move closer, hold still" while they are still standing
 * there turns a failed screening into a two-second adjustment.
 *
 * The sampling is deliberately slow (1s) and skipped while a check is in flight. This runs
 * on checkpoint hardware, and a per-frame loop would cost more than the guidance is worth.
 */
export default function FaceCapture({
  onCapture,
  label = 'Live capture',
  disabled = false,
  showQuality = true,
  source = 'live',
}) {
  const videoRef = useRef(null)
  const canvasRef = useRef(null)
  const streamRef = useRef(null)
  const inFlightRef = useRef(false)

  const [active, setActive] = useState(false)
  const [error, setError] = useState(null)
  const [dimensions, setDimensions] = useState(null)
  const [quality, setQuality] = useState(null)

  useEffect(() => {
    return () => stopStream()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /** Grabs the current frame as a JPEG blob, mirrored to match what the officer sees. */
  const grabFrame = useCallback((jpegQuality = 0.92) => {
    const video = videoRef.current
    const canvas = canvasRef.current
    if (!video || !canvas || !video.videoWidth) return Promise.resolve(null)

    canvas.width = video.videoWidth
    canvas.height = video.videoHeight

    const ctx = canvas.getContext('2d')
    ctx.setTransform(1, 0, 0, 1, 0, 0)
    ctx.translate(canvas.width, 0)
    ctx.scale(-1, 1)
    ctx.drawImage(video, 0, 0)
    ctx.setTransform(1, 0, 0, 1, 0, 0)

    return new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', jpegQuality))
  }, [])

  // Live quality sampling while the camera is on.
  useEffect(() => {
    if (!active || !showQuality) {
      setQuality(null)
      return undefined
    }

    let cancelled = false

    const sample = async () => {
      if (inFlightRef.current || cancelled) return
      inFlightRef.current = true
      try {
        // Lower JPEG quality for the preview: this frame is measured, not stored, and
        // the metrics that matter are unaffected by mild compression.
        const blob = await grabFrame(0.7)
        if (!blob || cancelled) return
        const result = await checkFaceQuality(
          new File([blob], 'preview.jpg', { type: 'image/jpeg' }),
          source,
        )
        if (!cancelled) setQuality(result)
      } finally {
        inFlightRef.current = false
      }
    }

    sample()
    const timer = setInterval(sample, 1000)
    return () => {
      cancelled = true
      clearInterval(timer)
    }
  }, [active, showQuality, source, grabFrame])

  async function startCamera() {
    try {
      setError(null)
      const stream = await navigator.mediaDevices.getUserMedia({
        video: {
          width: { ideal: 1280 },
          height: { ideal: 720 },
          facingMode: 'user',
        },
        audio: false,
      })
      streamRef.current = stream
      if (videoRef.current) {
        videoRef.current.srcObject = stream
        videoRef.current.onloadedmetadata = () => {
          videoRef.current.play()
          setActive(true)
          setDimensions({
            width: videoRef.current.videoWidth,
            height: videoRef.current.videoHeight,
          })
        }
      }
    } catch (e) {
      setError(
        e.name === 'NotAllowedError'
          ? 'Camera access denied. Allow camera access in your browser settings.'
          : e.name === 'NotFoundError'
            ? 'No camera found. Connect a camera and try again.'
            : `Camera error: ${e.message}`,
      )
    }
  }

  function stopStream() {
    if (streamRef.current) {
      streamRef.current.getTracks().forEach((t) => t.stop())
      streamRef.current = null
    }
    setActive(false)
    setQuality(null)
  }

  const capture = useCallback(async () => {
    const blob = await grabFrame(0.92)
    if (blob) {
      onCapture(new File([blob], 'live-capture.jpg', { type: 'image/jpeg' }))
    }
  }, [grabFrame, onCapture])

  const verdict = readQuality(quality)

  return (
    <div className="face-capture">
      <span className="label-text">{label}</span>

      <div className={`camera-viewport ${active ? 'is-active' : ''}`}>
        <video
          ref={videoRef}
          className="camera-video"
          muted
          playsInline
          style={{ transform: 'scaleX(-1)' }}
        />
        <canvas ref={canvasRef} className="visually-hidden" />

        {!active && !error && (
          <div className="camera-placeholder">
            <svg viewBox="0 0 24 24" width="32" height="32" aria-hidden="true">
              <path
                d="M15 10l4.553-2.276A1 1 0 0121 8.618v6.764a1 1 0 01-1.447.894L15 14M5 18h8a2 2 0 002-2V8a2 2 0 00-2-2H5a2 2 0 00-2 2v8a2 2 0 002 2z"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.6"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
            <span>Camera not active</span>
          </div>
        )}

        {active && (
          <div className="camera-overlay">
            <div className={`face-guide face-guide-${verdict.state}`} />
            {showQuality && (
              <div className={`capture-hint capture-hint-${verdict.state}`} role="status">
                <span className="capture-hint-dot" aria-hidden="true" />
                <span>{verdict.message}</span>
              </div>
            )}
          </div>
        )}
      </div>

      {error && <div className="camera-error">{error}</div>}

      {active && showQuality && verdict.checks.length > 0 && (
        <ul className="quality-chips">
          {verdict.checks.map((check) => (
            <li
              key={check.name}
              className={check.passed ? 'quality-chip is-ok' : 'quality-chip is-bad'}
              title={check.reason}
            >
              {QUALITY_LABELS[check.name] ?? check.name}
            </li>
          ))}
        </ul>
      )}

      <div className="camera-actions">
        {!active ? (
          <button type="button" className="primary" onClick={startCamera} disabled={disabled}>
            Start camera
          </button>
        ) : (
          <>
            <button type="button" className="primary" onClick={capture} disabled={disabled}>
              Capture
            </button>
            <button type="button" onClick={stopStream}>
              Stop
            </button>
          </>
        )}
      </div>

      {dimensions && (
        <div className="hint">
          Camera: {dimensions.width} x {dimensions.height}
          {verdict.score !== null && ` | Frame quality: ${Math.round(verdict.score * 100)}%`}
        </div>
      )}
    </div>
  )
}

const QUALITY_LABELS = {
  resolution: 'Distance',
  sharpness: 'Focus',
  exposure: 'Lighting',
  contrast: 'Contrast',
  pose: 'Head angle',
  detection: 'Detection',
}

/**
 * Turns the service's quality payload into one instruction.
 *
 * Only the single most useful correction is shown. An officer looking at a traveller does
 * not read a list; they need the one thing to change right now, and the chips below carry
 * the rest for anyone who wants it.
 */
function readQuality(payload) {
  if (!payload) {
    return { state: 'idle', message: 'Checking frame...', score: null, checks: [] }
  }

  if (!payload.faceFound) {
    return {
      state: 'bad',
      message: 'No face detected - look at the camera',
      score: null,
      checks: [],
    }
  }

  const quality = payload.quality ?? {}
  const checks = quality.checks ?? []

  if (payload.facesDetected > 1) {
    return {
      state: 'bad',
      message: `${payload.facesDetected} faces in frame - only the traveller should be visible`,
      score: quality.score ?? null,
      checks,
    }
  }

  if (quality.usable) {
    return { state: 'good', message: 'Ready to capture', score: quality.score ?? null, checks }
  }

  const failed = checks.filter((c) => !c.passed)
  const worst = failed.length
    ? failed.reduce((a, b) => (a.score <= b.score ? a : b))
    : null

  return {
    state: 'bad',
    message: worst
      ? (QUALITY_ADVICE[worst.name] ?? worst.reason)
      : (quality.advice || 'Frame quality is too low to compare reliably'),
    score: quality.score ?? null,
    checks,
  }
}

const QUALITY_ADVICE = {
  resolution: 'Move closer to the camera',
  sharpness: 'Hold still - the image is blurred',
  exposure: 'Adjust the lighting - too dark or too bright',
  contrast: 'Improve the lighting - the face is washed out',
  pose: 'Look straight at the camera',
  detection: 'Face only weakly detected - re-position',
}
