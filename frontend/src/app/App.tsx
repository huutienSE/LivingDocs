import { useEffect, useState } from 'react';
import { BrowserRouter, Link, useNavigate } from 'react-router-dom';
import { isAuthenticated, logout, subscribe } from '../api/client/session';
import { AppRoutes } from '../routes/routes';

function TopBar() {
  const navigate = useNavigate();
  const [authed, setAuthed] = useState(isAuthenticated());
  useEffect(() => subscribe(setAuthed), []);

  return (
    <header className="topbar">
      <Link to="/review" className="brand">
        LivingDocs
      </Link>
      {authed && (
        <button
          type="button"
          className="link"
          onClick={async () => {
            await logout().catch(() => undefined);
            navigate('/login', { replace: true });
          }}
        >
          Đăng xuất
        </button>
      )}
    </header>
  );
}

export function App() {
  return (
    <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <TopBar />
      <main className="container">
        <AppRoutes />
      </main>
    </BrowserRouter>
  );
}
