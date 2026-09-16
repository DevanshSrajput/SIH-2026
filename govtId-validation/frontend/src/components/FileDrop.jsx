import { useCallback, useEffect, useRef, useState } from 'react'

/*
 * Image picker with a thumbnail and a resolution readout.
 *
 * The readout is not decoration. A capture too small to resolve the machine-readable zone
 * produces the same shape of output as a forgery - missing fields and a rejection - and an
 * officer cannot tell those two apart after the fact. Saying "this image is too small" at
 * the point of upload is the only place that distinction is cheap to make.
 *
 * The threshold below is about the document image, not the screen: an MRZ line is 44
 * characters across the full width of the page, so under roughly 1000px of width the
 * characters fall below what any OCR engine resolves reliably.
 */

const ADVISORY_WIDTH = 1000
const MINIMUM_WIDTH = 600

export default function FileDrop({ id, file, onChange, label, hint, accept = 'image/*' }) {
  const [preview, setPreview] = useState(null)
  const [dimensions, setDimensions] = useState(null)
  const [dragging, setDragging] = useState(false)
  const inputRef = useRef(null)

  useEffect(() => {
    if (!file) {
      setPreview(null)
      setDimensions(null)
      return undefined
    }
    const url = URL.createObjectURL(file)
    setPreview(url)

    const probe = new Image()
    probe.onload = () => setDimensions({ width: probe.naturalWidth, height: probe.naturalHeight })
    probe.onerror = () => setDimensions(null)
    probe.src = url

    return () => URL.revokeObjectURL(url)
  }, [file])

  const accepted = useCallback(
    (list) => {
      const next = list?.[0]
      if (next) onChange(next)
    },
    [onChange],
  )

  function onDrop(event) {
    event.preventDefault()
    setDragging(false)
    accepted(event.dataTransfer?.files)
  }

  const quality = qualityOf(dimensions)

  return (
    <div className="field">
      <span className="label-text" id={`${id}-label`}>
        {label}
      </span>

      <div
        className={`dropzone${dragging ? ' is-dragging' : ''}${file ? ' has-file' : ''}`}
        onDragOver={(e) => {
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={onDrop}
        onClick={() => inputRef.current?.click()}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ' ') {
            e.preventDefault()
            inputRef.current?.click()
          }
        }}
        role="button"
        tabIndex={0}
        aria-labelledby={`${id}-label`}
      >
        <input
          ref={inputRef}
          id={id}
          type="file"
          accept={accept}
          className="visually-hidden"
          onChange={(e) => accepted(e.target.files)}
        />

        {preview ? (
          <div className="dropzone-filled">
            <img className="dropzone-thumb" src={preview} alt="" />
            <div className="dropzone-meta">
              <div className="dropzone-name">{file.name}</div>
              <div className="dropzone-sub">
                {dimensions ? `${dimensions.width} x ${dimensions.height}` : 'measuring...'}
                {' - '}
                {formatBytes(file.size)}
              </div>
              {quality && <div className={`quality quality-${quality.level}`}>{quality.text}</div>}
              <button
                type="button"
                className="link-button"
                onClick={(e) => {
                  e.stopPropagation()
                  onChange(null)
                }}
              >
                Remove
              </button>
            </div>
          </div>
        ) : (
          <div className="dropzone-empty">
            <svg viewBox="0 0 24 24" width="26" height="26" aria-hidden="true">
              <path
                d="M12 16V4m0 0L7.5 8.5M12 4l4.5 4.5M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3"
                fill="none"
                stroke="currentColor"
                strokeWidth="1.6"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
            </svg>
            <div>
              <strong>Drop an image</strong> or click to browse
            </div>
          </div>
        )}
      </div>

      {hint && <div className="hint">{hint}</div>}
    </div>
  )
}

/**
 * Advisory only - the console never blocks an upload. An officer may have nothing better
 * than a poor capture, and refusing to screen it would be worse than screening it with a
 * warning attached.
 */
function qualityOf(dimensions) {
  if (!dimensions) return null
  if (dimensions.width < MINIMUM_WIDTH) {
    return {
      level: 'bad',
      text: 'Too small to read reliably - expect missing fields. Re-capture if you can.',
    }
  }
  if (dimensions.width < ADVISORY_WIDTH) {
    return { level: 'warn', text: 'Low resolution - the coded lines may not read cleanly.' }
  }
  return { level: 'good', text: 'Resolution looks sufficient.' }
}

function formatBytes(bytes) {
  if (!bytes && bytes !== 0) return ''
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
