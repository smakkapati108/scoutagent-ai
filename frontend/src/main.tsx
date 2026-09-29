import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, NavLink, Route, Routes } from 'react-router-dom';
import { api } from './api';
import { ErrorBox, Loading, useAsync } from './components/common';
import { Dashboard } from './pages/Dashboard';
import { MatchupPage } from './pages/MatchupPage';
import { ScoutingPage } from './pages/ScoutingPage';
import { TeamPage } from './pages/TeamPage';
import './styles.css';

function App() {
  const meta = useAsync(api.meta, []);
  return (
    <div className="shell">
      <nav className="sidebar">
        <div className="brand"><span className="brand-mark">SA</span>ScoutAgent AI</div>
        <NavLink to="/" end className="nav-link">Overview</NavLink>
        <NavLink to="/teams" className="nav-link">Teams</NavLink>
        <NavLink to="/matchup" className="nav-link">Matchup predictor</NavLink>
        <NavLink to="/scouting" className="nav-link">Scouting agents</NavLink>
        <div className="sidebar-foot">Synthetic league data · Java + PostgreSQL + Claude</div>
      </nav>
      <main className="main">
        {meta.error && <ErrorBox message={`Backend unavailable: ${meta.error}`} />}
        {!meta.data ? (!meta.error && <Loading />) : (
          <Routes>
            <Route path="/" element={<Dashboard meta={meta.data} />} />
            <Route path="/teams" element={<TeamPage meta={meta.data} />} />
            <Route path="/teams/:abbr" element={<TeamPage meta={meta.data} />} />
            <Route path="/matchup" element={<MatchupPage meta={meta.data} />} />
            <Route path="/scouting" element={<ScoutingPage meta={meta.data} />} />
          </Routes>
        )}
      </main>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </React.StrictMode>,
);
