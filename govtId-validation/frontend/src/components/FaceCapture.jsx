import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * Webcam-based face capture component for live authentication.
 *
 * Provides a live camera feed with face detection overlay and a capture button.
 * When the browser supports the Face Detection API, it shows a real-time face
 * boundary box to guide the officer into position.
 */
export default function FaceCapture({ onCapture, label = 'Live capture', disabled = false }) {
  const videoRef = useRef(null)
  const canvasRef = useRef(null)
  const streamRef = useRef(null)
  const [active, setActive] = useState(false)
  const [error, setError] = useState(null)
  const [dimensions, setDimensions] = useState(null)

  useEffect(() => {
    return () => stopStream()
  }, [])

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
  }

  const capture = useCallback(() => {
    if (!videoRef.current || !canvasRef.current) return

    const video = videoRef.current
    const canvas = canvasRef.current
    canvas.width = video.videoWidth
    canvas.height = video.videoHeight

    const ctx = canvas.getContext('2d')
    // Mirror the image so the officer sees themselves naturally
    ctx.translate(canvas.width, 0)
    ctx.scale(-1, 1)
    ctx.drawImage(video, 0, 0)
    ctx.setTransform(1, 0, 0, 1, 0, 0)

    canvas.toBlob(
      (blob) => {
        if (blob) {
          const file = new File([blob], 'live-capture.jpg', { type: 'image/jpeg' })
          onCapture(file)
        }
      },
      'image/jpeg',
      0.92,
    )
  }, [onCapture])

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
            <div className="face-guide" />
          </div>
        )}
      </div>

      {error && <div className="camera-error">{error}</div>}

      <div className="camera-actions">
        {!active ? (
          <button
            type="button"
            className="primary"
            onClick={startCamera}
            disabled={disabled}
          >
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
        </div>
      )}
    </div>
  )
}
