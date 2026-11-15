/**
 * Session handling per LD-API-001 §2.0:
 *  - short-lived access JWT is held in memory only (never localStorage/sessionStorage/URL);
 *  - the opaque refresh token lives in an HttpOnly cookie set by S01 and is never readable here;
 *  - refresh/logout are cookie-bearing, so they send credentials and a CSRF header.
 */
import { ApiError, NetworkError, toProblem } from './problem';

export interface AccessTokenView {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
}

type Listener = (authenticated: boolean) => void;

let accessToken: string | null = null;
let refreshInFlight: Promise<string | null> | null = null;
const listeners = new Set<Listener>();

export const AUTH_PATHS = {
  login: '/api/v1/auth/login',
  refresh: '/api/v1/auth/refresh',
  logout: '/api/v1/auth/logout',
} as const;

export function getAccessToken(): string | null {
  return accessToken;
}

export function isAuthenticated(): boolean {
  return accessToken !== null;
}

export function subscribe(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function setAccessToken(token: string | null): void {
  const changed = (token !== null) !== (accessToken !== null);
  accessToken = token;
  if (changed) listeners.forEach((l) => l(token !== null));
}

export function clearSession(): void {
  setAccessToken(null);
}

/**
 * CSRF token for cookie-bearing endpoints. The concrete mechanism is a pending
 * Security Configuration Decision (LD-API-001 §2.0); this follows the Spring
 * Security cookie convention (XSRF-TOKEN -> X-XSRF-TOKEN) when the cookie exists.
 */
function csrfHeaders(): Record<string, string> {
  const match = typeof document !== 'undefined'
    ? document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/)
    : null;
  return match ? { 'X-XSRF-TOKEN': decodeURIComponent(match[1]) } : {};
}

async function postAuth(path: string, body?: unknown, withCsrf = false): Promise<Response> {
  try {
    return await fetch(path, {
      method: 'POST',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...(withCsrf ? csrfHeaders() : {}),
      },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
  } catch (cause) {
    throw new NetworkError(cause);
  }
}

export async function login(email: string, password: string): Promise<void> {
  const response = await postAuth(AUTH_PATHS.login, { email, password });
  if (!response.ok) throw new ApiError(await toProblem(response));
  const view = (await response.json()) as AccessTokenView;
  setAccessToken(view.accessToken);
}

/**
 * Rotates the session using the refresh cookie. Concurrent callers share one
 * request so a burst of 401s cannot trigger refresh-token reuse detection.
 * Returns the new access token, or null when the session is gone.
 */
export function refreshAccessToken(): Promise<string | null> {
  if (!refreshInFlight) {
    refreshInFlight = (async () => {
      try {
        const response = await postAuth(AUTH_PATHS.refresh, undefined, true);
        if (!response.ok) {
          setAccessToken(null);
          return null;
        }
        const view = (await response.json()) as AccessTokenView;
        setAccessToken(view.accessToken);
        return view.accessToken;
      } catch {
        setAccessToken(null);
        return null;
      } finally {
        refreshInFlight = null;
      }
    })();
  }
  return refreshInFlight;
}

export async function logout(): Promise<void> {
  try {
    await postAuth(AUTH_PATHS.logout, undefined, true);
  } finally {
    // The server may keep the short-lived JWT valid until expiry; we drop it locally regardless.
    setAccessToken(null);
  }
}
