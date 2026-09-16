import { moduleLabel, moduleNote } from '../findings.js'

/**
 * What actually ran, and what did not.
 *
 * This matters more than it looks. The system deliberately reports SKIPPED or FAILED
 * rather than inventing a plausible number, which means "no findings" from a module can
 * mean two opposite things: it checked and found nothing, or it never checked at all. An
 * officer deciding on this evidence has to be able to see which.
 */

const STATUS_WORDING = {
  COMPLETED: 'Ran',
  SKIPPED: 'Not run',
  FAILED: 'Could not run',
}

export default function ModuleTimeline({ results = [] }) {
  if (!results.length) return null

  return (
    <ol className="timeline">
      {results.map((result) => {
        const count = result.flags?.length ?? 0
        return (
          <li key={result.module} className={`timeline-item status-${result.status}`}>
            <span className="timeline-dot" aria-hidden="true" />
            <div className="timeline-body">
              <div className="timeline-head">
                <span className="module-name">{moduleLabel(result.module)}</span>
                <span className={`status-pill status-${result.status}`}>
                  {STATUS_WORDING[result.status] ?? result.status}
                </span>
              </div>
              <div className="module-note">
                {moduleNote(result.module)}
                {result.durationMillis != null && ` - ${result.durationMillis} ms`}
                {count > 0 && ` - ${count} finding${count === 1 ? '' : 's'}`}
              </div>
              {/* The API calls this `note`. On a skipped or failed module it carries the
                  reason, which is the whole point of showing the module at all - without
                  it the row says a check did not happen and refuses to say why. */}
              {result.note && result.status !== 'COMPLETED' && (
                <div className="timeline-reason">{result.note}</div>
              )}
            </div>
          </li>
        )
      })}
    </ol>
  )
}
