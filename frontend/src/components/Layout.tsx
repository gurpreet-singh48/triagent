import { NavLink, Outlet } from 'react-router-dom'

export function Layout() {
  return (
    <div className="app-shell">
      <header className="app-header">
        <div className="app-header-inner">
          <span className="brand">Triagent</span>
          <nav className="nav">
            <NavLink to="/" end className={({ isActive }) => (isActive ? 'nav-link active' : 'nav-link')}>
              Dashboard
            </NavLink>
            <NavLink to="/chat" className={({ isActive }) => (isActive ? 'nav-link active' : 'nav-link')}>
              Chat
            </NavLink>
          </nav>
        </div>
      </header>
      <main className="app-main">
        <Outlet />
      </main>
    </div>
  )
}
