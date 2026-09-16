export default function Badge({ value, fallback = '--' }) {
  if (!value) {
    return <span className="badge badge-neutral">{fallback}</span>
  }
  return <span className={`badge badge-${value}`}>{value}</span>
}
