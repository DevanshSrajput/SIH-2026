import { NavLink, Navigate, Route, Routes } from 'react-router-dom'
import ScreenPage from './pages/ScreenPage.jsx'
import CasesPage from './pages/CasesPage.jsx'
import CaseDetailPage from './pages/CaseDetailPage.jsx'
import WatchlistPage from './pages/WatchlistPage.jsx'
import DashboardPage from './pages/DashboardPage.jsx'
import FaceAuthPage from './pages/FaceAuthPage.jsx'
import ErrorBoundary from './components/ErrorBoundary.jsx'

export default function App() {
  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          Border Document Screening<span>officer console</span>
        </div>
        <nav className="nav">
          <NavLink to="/screen">Screen</NavLink>
          <NavLink to="/face">Face Auth</NavLink>
          <NavLink to="/cases">Cases</NavLink>
          <NavLink to="/watchlist">Watchlist</NavLink>
          <NavLink to="/dashboard">Dashboard</NavLink>
        </nav>
      </header>

      <main>
        {/*
          Keyed on the route so a crash on one page clears when the officer navigates
          away, rather than leaving the boundary stuck in its error state on a page that
          would have rendered perfectly well.
        */}
        <Routes>
          <Route path="/" element={<Navigate to="/screen" replace />} />
          <Route
            path="/screen"
            element={
              <ErrorBoundary title="This page could not be displayed">
                <ScreenPage />
              </ErrorBoundary>
            }
          />
          <Route
            path="/face"
            element={
              <ErrorBoundary title="This page could not be displayed">
                <FaceAuthPage />
              </ErrorBoundary>
            }
          />
          <Route
            path="/cases"
            element={
              <ErrorBoundary title="This page could not be displayed">
                <CasesPage />
              </ErrorBoundary>
            }
          />
          <Route
            path="/cases/:reference"
            element={
              <ErrorBoundary title="This page could not be displayed">
                <CaseDetailPage />
              </ErrorBoundary>
            }
          />
          <Route
            path="/watchlist"
            element={
              <ErrorBoundary title="This page could not be displayed">
                <WatchlistPage />
              </ErrorBoundary>
            }
          />
          <Route
            path="/dashboard"
            element={
              <ErrorBoundary title="This page could not be displayed">
                <DashboardPage />
              </ErrorBoundary>
            }
          />
        </Routes>
      </main>
    </div>
  )
}
