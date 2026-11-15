import { useEffect, useState, type ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { isAuthenticated, refreshAccessToken, subscribe } from '../../api/client/session';
import { LoadingState } from '../../shared/ui/LoadingState';

/**
 * The access token lives only in memory, so after a page reload we try one
 * silent refresh (refresh cookie) before sending the user to the login page.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const location = useLocation();
  const [authed, setAuthed] = useState(isAuthenticated());
  const [checking, setChecking] = useState(!isAuthenticated());

  useEffect(() => subscribe(setAuthed), []);

  useEffect(() => {
    if (isAuthenticated()) return;
    let active = true;
    refreshAccessToken().finally(() => {
      if (active) setChecking(false);
    });
    return () => {
      active = false;
    };
  }, []);

  if (checking) return <LoadingState message="Đang kiểm tra phiên đăng nhập…" />;
  if (!authed) return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  return <>{children}</>;
}
