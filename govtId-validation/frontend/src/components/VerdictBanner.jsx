/**
 * The headline an officer reads first: the score, the recommended action, and the
 * reasoning behind it. The rationale is shown, not hidden behind a click, so the
 * recommendation can be challenged rather than merely followed.
 *
 * The score is drawn as a filled arc as well as a number. Under time pressure the fill
 * reads before the digits do, and it makes the distance to the next threshold visible -
 * a 34 and a 36 are one point apart but sit either side of a referral.
 */

const WORDING = {
  CLEAR: {
    label: 'Clear to proceed',
    sub: 'No material findings on document grounds.',
  },
  REVIEW: {
    label: 'Refer to officer',
    sub: 'A human must inspect this before deciding.',
  },
  REJECT: {
    label: 'Do not accept',
    sub: 'Strong evidence of forgery, tampering or a watchlist hit.',
  },
}

export default function VerdictBanner({ risk }) {
  if (!risk) {
    return (
      <div className="verdict-banner verdict-REVIEW">
        <ScoreDial score={null} />
        <div className="verdict-body">
          <div className="verdict-label">No assessment</div>
          <div className="verdict-explanation">
            This case did not complete screening, so no recommendation was produced.
          </div>
        </div>
      </div>
    )
  }

  const wording = WORDING[risk.verdict] ?? { label: risk.verdict, sub: null }

  return (
    <div className={`verdict-banner verdict-${risk.verdict}`}>
      <ScoreDial score={risk.score} verdict={risk.verdict} />
      <div className="verdict-body">
        <div className="verdict-head">
          <VerdictIcon verdict={risk.verdict} />
          <span className="verdict-label">{wording.label}</span>
          {risk.band && <span className="verdict-band">{risk.band} risk</span>}
        </div>
        {wording.sub && <div className="verdict-sub">{wording.sub}</div>}
        <div className="verdict-explanation">{risk.explanation}</div>
      </div>
    </div>
  )
}

function ScoreDial({ score, verdict }) {
  const value = typeof score === 'number' ? Math.max(0, Math.min(100, score)) : null
  const radius = 34
  const circumference = 2 * Math.PI * radius
  const filled = value === null ? 0 : (value / 100) * circumference

  return (
    <div className={`score-dial score-dial-${verdict ?? 'NONE'}`}>
      <svg viewBox="0 0 80 80" width="84" height="84" aria-hidden="true">
        <circle className="dial-track" cx="40" cy="40" r={radius} />
        <circle
          className="dial-value"
          cx="40"
          cy="40"
          r={radius}
          strokeDasharray={`${filled} ${circumference}`}
          transform="rotate(-90 40 40)"
        />
      </svg>
      <div className="dial-number">
        <span className="dial-score">{value === null ? '?' : value}</span>
        <span className="dial-unit">/100</span>
      </div>
    </div>
  )
}

function VerdictIcon({ verdict }) {
  const paths = {
    CLEAR: 'M5 12.5l4.5 4.5L19 7.5',
    REVIEW: 'M12 7v6m0 3.5v.5M12 3l9.5 17h-19L12 3z',
    REJECT: 'M7 7l10 10M17 7L7 17',
  }
  return (
    <svg className="verdict-icon" viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
      <path
        d={paths[verdict] ?? paths.REVIEW}
        fill="none"
        stroke="currentColor"
        strokeWidth="2.2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}
