import { NavLink, Navigate, Route, Routes } from 'react-router-dom'
import ScreenPage from './pages/ScreenPage.jsx'
import CasesPage from './pages/CasesPage.jsx'
import CaseDetailPage from './pages/CaseDetailPage.jsx'
import WatchlistPage from './pages/WatchlistPage.jsx'
import DashboardPage from './pages/DashboardPage.jsx'
import FaceAuthPage from './pages/FaceAuthPage.jsx'

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
        <Routes>
          <Route path="/" element={<Navigate to="/screen" replace />} />
          <Route path="/screen" element={<ScreenPage />} />
          <Route path="/face" element={<FaceAuthPage />} />
          <Route path="/cases" element={<CasesPage />} />
          <Route path="/cases/:reference" element={<CaseDetailPage />} />
          <Route path="/watchlist" element={<WatchlistPage />} />
          <Route path="/dashboard" element={<DashboardPage />} />
        </Routes>
      </main>
    </div>
  )
}
