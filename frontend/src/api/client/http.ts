/**
 * Thin fetch wrapper for Gateway calls:
 *  - attaches `Authorization: Bearer <access JWT>` from memory;
 *  - on 401 refreshes once (single-flight) and replays the same request
 *    (same Idempotency-Key, same If-Match) exactly once;
 *  - surfaces failures as ApiError (problem+json) or NetworkError;
 *  - exposes the strong ETag so callers can send If-Match on mutations.
 * Never sends role/tenant headers: scope is derived server-side from the token.
 */
import { ApiError, NetworkError, toProblem } from './problem';
import { clearSession, getAccessToken, refreshAccessToken } from './session';

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  query?: Record<string, string | number | undefined>;
  body?: unknown;
  ifMatch?: string;
  idempotencyKey?: string;
  signal?: AbortSignal;
}

export interface ApiResponse<T> {
  data: T;
  etag: string | null;
  status: number;
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
  if (!query) return path;
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== '') params.set(key, String(value));
  }
  const qs = params.toString();
  return qs ? `${path}?${qs}` : path;
}

async function send(url: string, options: RequestOptions, token: string | null): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json, application/problem+json' };
  if (token) headers.Authorization = `Bearer ${token}`;
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (options.ifMatch) headers['If-Match'] = options.ifMatch;
  if (options.idempotencyKey) headers['Idempotency-Key'] = options.idempotencyKey;

  try {
    return await fetch(url, {
      method: options.method ?? 'GET',
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      credentials: 'same-origin',
      signal: options.signal,
    });
  } catch (cause) {
    if (cause instanceof DOMException && cause.name === 'AbortError') throw cause;
    throw new NetworkError(cause);
  }
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<ApiResponse<T>> {
  const url = buildUrl(path, options.query);
  let response = await send(url, options, getAccessToken());

  if (response.status === 401) {
    const fresh = await refreshAccessToken();
    if (fresh) {
      response = await send(url, options, fresh);
    }
    if (response.status === 401) {
      clearSession();
    }
  }

  if (!response.ok) {
    throw new ApiError(await toProblem(response));
  }

  const etag = response.headers.get('ETag');
  const data = response.status === 204 ? (undefined as T) : ((await response.json()) as T);
  return { data, etag, status: response.status };
}

/** One key per user intent; reuse the same key when the user retries that intent. */
export function newIdempotencyKey(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return `idem-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}
