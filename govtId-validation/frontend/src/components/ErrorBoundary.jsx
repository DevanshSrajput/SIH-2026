import { Component } from 'react'

/**
 * Catches a render-time crash in one part of the console and keeps the rest usable.
 *
 * Without this, a single malformed case - an unexpected null in a module result, say -
 * unmounts the whole application and leaves an officer at a blank page mid-shift, with no
 * indication whether the screening succeeded. A contained failure that names itself and
 * offers a retry is recoverable; a white screen is a call to IT.
 *
 * This only catches errors thrown during render. Rejected promises from API calls are
 * handled where they are made, because those have a specific message worth showing.
 */
export default class ErrorBoundary extends Component {
  constructor(props) {
    super(props)
    this.state = { error: null }
  }

  static getDerivedStateFromError(error) {
    return { error }
  }

  componentDidCatch(error, info) {
    // Kept in the browser console so the stack is recoverable when someone reports it.
    console.error('Console error boundary caught:', error, info?.componentStack)
  }

  render() {
    const { error } = this.state
    if (!error) {
      return this.props.children
    }

    return (
      <div className="panel error-boundary" role="alert">
        <h2>{this.props.title ?? 'This panel could not be displayed'}</h2>
        <p>
          Something went wrong rendering this section. The rest of the console is still
          working, and nothing you submitted has been lost.
        </p>
        <p className="error-boundary-detail">{String(error.message || error)}</p>
        <div className="button-row">
          <button type="button" className="primary" onClick={() => this.setState({ error: null })}>
            Try again
          </button>
          <button type="button" onClick={() => window.location.reload()}>
            Reload the console
          </button>
        </div>
      </div>
    )
  }
}
